/*
 * On-device image generation with ncnn (Tencent), adapted from EdVince's
 * Stable-Diffusion-NCNN (BSD 3-Clause, https://github.com/EdVince/Stable-Diffusion-NCNN).
 * The model files (ncnn .param/.bin) are Stable-Diffusion-v1.5-architecture weights converted
 * for ncnn; the canonical public source is Hugging Face `yui0/stable_diffusion`.
 *
 * Adaptations from the original: loads weights from a folder on disk (no AAssetManager), no
 * OpenCV (the normal-distribution sampler initializer uses C++ <random>; pixels go out as a
 * plain RGBA byte array instead of an android.graphics.Bitmap), and a per-diffusion-step JNI
 * progress callback so the app can show "step n/N" while it draws.
 *
 * Compute path: the original Android sample ran ncnn on the CPU (use_vulkan_compute = false;
 * fp16-packed arithmetic is fast enough on recent flagships). We additionally try the GPU
 * first when the phone exposes a Vulkan compute device: ncnn speaks Vulkan compute, a
 * completely different driver path from the OpenCL one MediaPipe's engine fails on
 * (Xclipse 920 clCreateImage). The GPU attempt is bounded: some drivers hang forever inside
 * device/pipeline setup, so it runs on a worker thread and init waits at most [gpuTimeoutMs]
 * before detaching it and taking the exact CPU path of the original; nothing else changes.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <map>
#include <stack>
#include <queue>
#include <cmath>
#include <cstring>
#include <random>
#include <fstream>
#include <algorithm>
#include <regex>
#include <memory>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <chrono>

#include "net.h"
#include "cpu.h"
#include "benchmark.h"
// platform.h (included through net.h) defines NCNN_VULKAN for the vulkan prebuilt and pulls
// in gpu.h with it; keep an explicit include out so a cpu-only build compiles unchanged.

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "sdncnn", __VA_ARGS__)

using namespace std;

// ---------------------------------------------------------------- prompt handling (CLIP ---

class PromptSlover {
public:
    int load(const string& dir, bool useGpu = false) {
        net.opt.lightmode = true;
        net.opt.use_vulkan_compute = useGpu;
        net.opt.use_winograd_convolution = false;
        net.opt.use_sgemm_convolution = false;
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = true;
        net.opt.use_packing_layout = true;
        net.opt.num_threads = ncnn::get_big_cpu_count();
        if (useGpu)
        {
#if NCNN_VULKAN
            net.set_vulkan_device(ncnn::get_default_gpu_index());
#endif
        }

        if (net.load_param((dir + "/clip.param").c_str()) < 0) return -1;
        if (net.load_model((dir + "/clip.bin").c_str()) < 0) return -1;

        ifstream infile(dir + "/vocab.txt");
        string s;
        int idx = 0;
        while (getline(infile, s)) {
            s.erase(0, s.find_first_not_of(" \t\r\n"));
            s.erase(s.find_last_not_of(" \t\r\n") + 1);
            if (s.empty()) continue;
            tokenizer_token2idx.insert(pair<string, int>(s, idx));
            tokenizer_idx2token.insert(pair<int, string>(idx, s));
            idx++;
        }
        return infile.is_open() && idx > 0 ? 0 : -1;
    }

    void unload() {
        net.clear();
        tokenizer_token2idx.clear();
        tokenizer_idx2token.clear();
    }

    ncnn::Mat get_conditioning(string& prompt);

private:
    vector<pair<string, float>> parse_prompt_attention(string& texts);
    string whitespace_clean(string& text);
    vector<string> split(string strs);

    map<string, int> tokenizer_token2idx;
    map<int, string> tokenizer_idx2token;
    ncnn::Net net;
};

vector<pair<string, float>> PromptSlover::parse_prompt_attention(string& texts)
{
    vector<pair<string, float>> res;
    stack<int> round_brackets;
    stack<int> square_brackets;
    const float round_bracket_multiplier = 1.1;
    const float square_bracket_multiplier = 1 / 1.1;

    vector<string> ms;
    for (char c : texts) {
        string s = string(1, c);
        if (s == "(" || s == "[" || s == ")" || s == "]") {
            ms.push_back(s);
        }
        else {
            if (ms.size() < 1)
                ms.push_back("");
            string last = ms[ms.size() - 1];
            if (last == "(" || last == "[" || last == ")" || last == "]") {
                ms.push_back("");
            }
            ms[ms.size() - 1] += s;
        }
    }

    for (string text : ms) {
        if (text == "(") {
            round_brackets.push(res.size());
        }
        else if (text == "[") {
            square_brackets.push(res.size());
        }
        else if (text == ")" && round_brackets.size() > 0) {
            for (int p = round_brackets.top(); p < res.size(); p++) {
                res[p].second *= round_bracket_multiplier;
            }
            round_brackets.pop();
        }
        else if (text == "]" && square_brackets.size() > 0) {
            for (int p = square_brackets.top(); p < res.size(); p++) {
                res[p].second *= square_bracket_multiplier;
            }
            square_brackets.pop();
        }
        else {
            res.push_back(make_pair(text, 1.0));
        }
    }

    while (!round_brackets.empty()) {
        for (int p = round_brackets.top(); p < res.size(); p++) {
            res[p].second *= round_bracket_multiplier;
        }
        round_brackets.pop();
    }
    while (!square_brackets.empty()) {
        for (int p = square_brackets.top(); p < res.size(); p++) {
            res[p].second *= square_bracket_multiplier;
        }
        square_brackets.pop();
    }

    int i = 0;
    while (i + 1 < res.size()) {
        if (res[i].second == res[i + 1].second) {
            res[i].first += res[i + 1].first;
            auto it = res.begin();
            res.erase(it + i + 1);
        }
        else {
            i += 1;
        }
    }

    // The regex weighted syntax "word:1.2" from the original is dropped on purpose: the
    // bracket syntax above covers what agents actually write, and this keeps the parser
    // dependency-free.
    return res;
}

string PromptSlover::whitespace_clean(string& text)
{
    return regex_replace(text, regex("\\s+"), " ");
}

vector<string> PromptSlover::split(string strs)
{
    string str = whitespace_clean(strs);
    std::string::size_type pos;
    std::vector<std::string> result;
    str += " ";
    int size = str.size();
    for (int i = 0; i < size; i++) {
        pos = min(str.find(" ", i), str.find(",", i));
        if (pos < size) {
            {
                std::string s = str.substr(i, pos - i);
                string pat = string(1, str[pos]);
                if (s.length() > 0)
                    result.push_back(s + "</w>");
                if (pat != " ")
                    result.push_back(pat + "</w>");
                i = pos;
            }
        }
    }
    return result;
}

ncnn::Mat PromptSlover::get_conditioning(string& prompt)
{
    vector<pair<string, float>> parsed = parse_prompt_attention(prompt);

    // token 2 ids
    vector<vector<int>> tokenized;
    {
        for (auto& p : parsed) {
            vector<string> tokens = split(p.first);
            vector<int> ids;
            for (auto& token : tokens) {
                ids.push_back(tokenizer_token2idx.count(token) ? tokenizer_token2idx[token] : 49407);
            }
            tokenized.push_back(ids);
        }
    }

    vector<int> remade_tokens;
    vector<float> multipliers;
    {
        int last_comma = -1;
        for (int it_tokenized = 0; it_tokenized < tokenized.size(); it_tokenized++) {
            vector<int> tokens = tokenized[it_tokenized];
            float weight = parsed[it_tokenized].second;

            int i = 0;
            while (i < tokens.size()) {
                int token = tokens[i];
                if (token == 267) {
                    last_comma = remade_tokens.size();
                }
                else if ((max(int(remade_tokens.size()), 1) % 75 == 0) && (last_comma != -1) && (remade_tokens.size() - last_comma <= 20)) {
                    last_comma += 1;
                    vector<int> reloc_tokens(remade_tokens.begin() + last_comma, remade_tokens.end());
                    vector<float> reloc_mults(multipliers.begin() + last_comma, multipliers.end());
                    vector<int> _remade_tokens_(remade_tokens.begin(), remade_tokens.begin() + last_comma);
                    remade_tokens = _remade_tokens_;
                    int length = remade_tokens.size();
                    int rem = ceil(length / 75.0) * 75 - length;
                    vector<int> tmp_token(rem, 49407);
                    remade_tokens.insert(remade_tokens.end(), tmp_token.begin(), tmp_token.end());
                    remade_tokens.insert(remade_tokens.end(), reloc_tokens.begin(), reloc_tokens.end());
                    vector<float> _multipliers_(multipliers.begin(), multipliers.end() - (multipliers.size() - last_comma));
                    vector<int> tmp_multipliers(rem, 1.0f);
                    _multipliers_.insert(_multipliers_.end(), tmp_multipliers.begin(), tmp_multipliers.end());
                    _multipliers_.insert(_multipliers_.end(), reloc_mults.begin(), reloc_mults.end());
                    multipliers = _multipliers_;
                }
                remade_tokens.push_back(token);
                multipliers.push_back(weight);
                i += 1;
            }
        }
        int prompt_target_length = ceil(max(int(remade_tokens.size()), 1) / 75.0) * 75;
        int tokens_to_add = prompt_target_length - remade_tokens.size();
        vector<int> tmp_token(tokens_to_add, 49407);
        remade_tokens.insert(remade_tokens.end(), tmp_token.begin(), tmp_token.end());
        vector<float> tmp_multipliers(tokens_to_add, 1.0f);
        multipliers.insert(multipliers.end(), tmp_multipliers.begin(), tmp_multipliers.end());
    }

    ncnn::Mat conds(768, 0);
    {
        while (remade_tokens.size() > 0) {
            vector<int> rem_tokens(remade_tokens.begin() + 75, remade_tokens.end());
            vector<float> rem_multipliers(multipliers.begin() + 75, multipliers.end());

            vector<int> current_tokens;
            vector<float> current_multipliers;
            if (remade_tokens.size() > 0) {
                current_tokens.insert(current_tokens.end(), remade_tokens.begin(), remade_tokens.begin() + 75);
                current_multipliers.insert(current_multipliers.end(), multipliers.begin(), multipliers.begin() + 75);
            }
            else {
                vector<int> tmp_token(75, 49407);
                current_tokens.insert(current_tokens.end(), tmp_token.begin(), tmp_token.end());
                vector<float> tmp_multipliers(75, 1.0f);
                current_multipliers.insert(current_multipliers.end(), tmp_multipliers.begin(), tmp_multipliers.end());
            }

            {
                ncnn::Mat token_mat = ncnn::Mat(77);
                token_mat.fill(49406);
                ncnn::Mat multiplier_mat = ncnn::Mat(77);
                multiplier_mat.fill(1.0f);

                int* token_ptr = token_mat;
                float* multiplier_ptr = multiplier_mat;
                for (int i = 0; i < 75; i++) {
                    token_ptr[i + 1] = int(current_tokens[i]);
                    multiplier_ptr[i + 1] = current_multipliers[i];
                }

                ncnn::Extractor ex = net.create_extractor();
                ex.set_light_mode(true);
                ex.input("token", token_mat);
                ex.input("multiplier", multiplier_mat);
                ex.input("cond", conds);
                ncnn::Mat new_conds;
                ex.extract("conds", new_conds);
                conds = new_conds;
            }

            remade_tokens = rem_tokens;
            multipliers = rem_multipliers;
        }
    }

    return conds;
}

// ------------------------------------------------------------------------ UNet sampler ---

class DiffusionSlover {
public:
    int load(const string& dir, bool useGpu = false) {
        ncnn::set_cpu_powersave(2);
        ncnn::set_omp_num_threads(ncnn::get_big_cpu_count());

        net.opt.lightmode = true;
        net.opt.use_vulkan_compute = useGpu;
        net.opt.use_winograd_convolution = false;
        net.opt.use_sgemm_convolution = false;
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = true;
        net.opt.use_packing_layout = true;
        net.opt.num_threads = ncnn::get_big_cpu_count();
        if (useGpu)
        {
#if NCNN_VULKAN
            net.set_vulkan_device(ncnn::get_default_gpu_index());
#endif
        }

        if (net.load_param((dir + "/unet.param").c_str()) < 0) return -1;
        if (net.load_model((dir + "/unet.bin").c_str()) < 0) return -1;

        ifstream in(dir + "/log_sigmas.bin", ios::in | ios::binary);
        if (!in.is_open()) return -1;
        in.read((char*)&log_sigmas, sizeof log_sigmas);
        in.close();

        return 0;
    }

    void unload() { net.clear(); }

    void clear_visited() { visited = ncnn::Mat(); }

    // One deterministic 4x32x32 standard-normal latent from [seed].
    ncnn::Mat randn_4_32_32(unsigned seed) {
        ncnn::Mat m(32, 32, 4);
        std::mt19937 gen(seed);
        std::normal_distribution<float> nd(0.f, 1.f);
        for (int c = 0; c < 4; c++) {
            float* p = m.channel(c);
            for (int hw = 0; hw < 32 * 32; hw++) p[hw] = nd(gen);
        }
        return m;
    }

    ncnn::Mat sampler_txt2img(int seed, int step, ncnn::Mat& c, ncnn::Mat& uc,
                              std::function<bool(int)> progress);

private:
    ncnn::Mat CFGDenoiser_CompVisDenoiser(ncnn::Mat& input, float sigma, ncnn::Mat cond, ncnn::Mat uncond);

    float log_sigmas[1000] = { 0 };
    const float guidance_scale = 7.5;

    ncnn::Net net;
    ncnn::Mat visited;
};

ncnn::Mat DiffusionSlover::CFGDenoiser_CompVisDenoiser(ncnn::Mat& input, float sigma, ncnn::Mat cond, ncnn::Mat uncond)
{
    // get_scalings
    float c_out = -1.0 * sigma;
    float c_in = 1.0 / sqrt(sigma * sigma + 1);

    // sigma_to_t
    float log_sigma = log(sigma);
    vector<float> dists(1000);
    for (int i = 0; i < 1000; i++) {
        if (log_sigma - log_sigmas[i] >= 0)
            dists[i] = 1;
        else
            dists[i] = 0;
        if (i == 0) continue;
        dists[i] += dists[i - 1];
    }
    int low_idx = min(int(max_element(dists.begin(), dists.end()) - dists.begin()), 1000 - 2);
    int high_idx = low_idx + 1;
    float low = log_sigmas[low_idx];
    float high = log_sigmas[high_idx];
    float w = (low - log_sigma) / (low - high);
    w = max(0.f, min(1.f, w));
    float t = (1 - w) * low_idx + w * high_idx;

    ncnn::Mat t_mat(1);
    t_mat[0] = t;

    ncnn::Mat c_in_mat(1);
    c_in_mat[0] = c_in;

    ncnn::Mat c_out_mat(1);
    c_out_mat[0] = c_out;

    ncnn::Mat v44;
    ncnn::Mat v83;
    ncnn::Mat v116;
    ncnn::Mat v163;
    ncnn::Mat v251;
    ncnn::Mat v337;
    ncnn::Mat v425;
    ncnn::Mat v511;
    ncnn::Mat v599;
    ncnn::Mat v627;
    ncnn::Mat v711;
    ncnn::Mat v725;
    ncnn::Mat v740;
    ncnn::Mat v755;
    ncnn::Mat v772;
    ncnn::Mat v858;
    ncnn::Mat v944;
    ncnn::Mat v1032;
    ncnn::Mat v1118;
    ncnn::Mat v1204;
    ncnn::Mat v1292;
    ncnn::Mat v1378;
    ncnn::Mat v1464;

    ncnn::Mat denoised_cond;
    {
        ncnn::Extractor ex = net.create_extractor();
        ex.set_light_mode(true);
        ex.input("in0", input);
        ex.input("in1", t_mat);
        ex.input("in2", cond);
        ex.input("c_in", c_in_mat);
        ex.input("c_out", c_out_mat);
        ex.extract("44", v44, 1);
        ex.extract("83", v83, 1);
        ex.extract("116", v116, 1);
        ex.extract("163", v163, 1);
        ex.extract("251", v251, 1);
        ex.extract("337", v337, 1);
        ex.extract("425", v425, 1);
        ex.extract("511", v511, 1);
        ex.extract("599", v599, 1);
        ex.extract("627", v627, 1);
        ex.extract("711", v711, 1);
        ex.extract("725", v725, 1);
        ex.extract("740", v740, 1);
        ex.extract("755", v755, 1);
        ex.extract("772", v772, 1);
        ex.extract("858", v858, 1);
        ex.extract("944", v944, 1);
        ex.extract("1032", v1032, 1);
        ex.extract("1118", v1118, 1);
        ex.extract("1204", v1204, 1);
        ex.extract("1292", v1292, 1);
        ex.extract("1378", v1378, 1);
        ex.extract("1464", v1464, 1);
        ex.extract("outout", denoised_cond);
    }

    ncnn::Mat denoised_uncond;
    {
        ncnn::Extractor ex = net.create_extractor();
        ex.set_light_mode(true);
        ex.input("in0", input);
        ex.input("in1", t_mat);
        ex.input("in2", uncond);
        ex.input("c_in", c_in_mat);
        ex.input("c_out", c_out_mat);
        ex.input("44", v44);
        ex.input("83", v83);
        ex.input("116", v116);
        ex.input("163", v163);
        ex.input("251", v251);
        ex.input("337", v337);
        ex.input("425", v425);
        ex.input("511", v511);
        ex.input("599", v599);
        ex.input("627", v627);
        ex.input("711", v711);
        ex.input("725", v725);
        ex.input("740", v740);
        ex.input("755", v755);
        ex.input("772", v772);
        ex.input("858", v858);
        ex.input("944", v944);
        ex.input("1032", v1032);
        ex.input("1118", v1118);
        ex.input("1204", v1204);
        ex.input("1292", v1292);
        ex.input("1378", v1378);
        ex.input("1464", v1464);
        ex.extract("outout", denoised_uncond);
    }

    for (int c = 0; c < 4; c++) {
        float* u_ptr = denoised_uncond.channel(c);
        float* c_ptr = denoised_cond.channel(c);
        for (int hw = 0; hw < 32 * 32; hw++) {
            (*u_ptr) = (*u_ptr) + guidance_scale * ((*c_ptr) - (*u_ptr));
            u_ptr++;
            c_ptr++;
        }
    }

    return denoised_uncond;
}

ncnn::Mat DiffusionSlover::sampler_txt2img(int seed, int step, ncnn::Mat& c, ncnn::Mat& uc,
                                           std::function<bool(int)> progress)
{
    // t_to_sigma
    vector<float> sigma(step);
    float delta = 0.0 - 999.0 / (step - 1);
    for (int i = 0; i < step; i++) {
        float t = 999.0 + i * delta;
        int low_idx = floor(t);
        int high_idx = ceil(t);
        float w = t - low_idx;
        sigma[i] = exp((1 - w) * log_sigmas[low_idx] + w * log_sigmas[high_idx]);
    }
    sigma.push_back(0.f);

    // init
    ncnn::Mat x_mat = randn_4_32_32(seed % 1000);
    float _norm_[4] = { sigma[0], sigma[0], sigma[0], sigma[0] };
    x_mat.substract_mean_normalize(0, _norm_);

    // sample_euler_ancestral
    for (int i = 0; i < sigma.size() - 1; i++) {
        double t1 = ncnn::get_current_time();
        ncnn::Mat denoised = CFGDenoiser_CompVisDenoiser(x_mat, sigma[i], c, uc);
        double t2 = ncnn::get_current_time();
        LOG("Step:%2d/%d\t%.0fms", i + 1, sigma.size() - 1, t2 - t1);

        float sigma_up = min(sigma[i + 1], sqrt(sigma[i + 1] * sigma[i + 1] * (sigma[i] * sigma[i] - sigma[i + 1] * sigma[i + 1]) / (sigma[i] * sigma[i])));
        float sigma_down = sqrt(sigma[i + 1] * sigma[i + 1] - sigma_up * sigma_up);

        ncnn::Mat randn = randn_4_32_32((unsigned)(seed + i + 1) % 1000);
        for (int cI = 0; cI < 4; cI++) {
            float* x_ptr = x_mat.channel(cI);
            float* d_ptr = denoised.channel(cI);
            float* r_ptr = randn.channel(cI);
            for (int hw = 0; hw < 32 * 32; hw++) {
                *x_ptr = *x_ptr + ((*x_ptr - *d_ptr) / sigma[i]) * (sigma_down - sigma[i]) + *r_ptr * sigma_up;
                x_ptr++;
                d_ptr++;
                r_ptr++;
            }
        }

        if (progress && (i + 1) < sigma.size() - 1) {
            if (!progress(i + 1)) return ncnn::Mat(); // abandoned
        }
    }

    ncnn::Mat out;
    out.clone_from(x_mat);
    return out;
}

// ------------------------------------------------------------------------ VAE decoder ---

class DecodeSlover {
public:
    int load(const string& dir, bool useGpu = false) {
        ncnn::set_cpu_powersave(0);
        ncnn::set_omp_num_threads(ncnn::get_big_cpu_count());

        net.opt.lightmode = true;
        net.opt.use_vulkan_compute = useGpu;
        net.opt.use_winograd_convolution = false;
        net.opt.use_sgemm_convolution = false;
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = true;
        net.opt.use_packing_layout = true;
        net.opt.num_threads = ncnn::get_big_cpu_count();
        if (useGpu)
        {
#if NCNN_VULKAN
            net.set_vulkan_device(ncnn::get_default_gpu_index());
#endif
        }

        if (net.load_param((dir + "/vae.param").c_str()) < 0) return -1;
        if (net.load_model((dir + "/vae.bin").c_str()) < 0) return -1;
        return 0;
    }

    void unload() { net.clear(); }

    ncnn::Mat decode(ncnn::Mat sample) {
        ncnn::Mat x_samples_ddim;
        {
            sample.substract_mean_normalize(0, factor);

            ncnn::Extractor ex = net.create_extractor();
            ex.set_light_mode(true);
            ex.input("input.1", sample);
            ex.extract("815", x_samples_ddim);

            x_samples_ddim.substract_mean_normalize(_mean_, _norm_);
        }
        return x_samples_ddim;
    }

private:
    const float factor[4] = { 1.0f / 0.18215f, 1.0f / 0.18215f, 1.0f / 0.18215f, 1.0f / 0.18215f };
    const float _mean_[3] = { -1.0f, -1.0f, -1.0f };
    const float _norm_[3] = { 127.5f, 127.5f, 127.5f };

    ncnn::Net net;
};

// ------------------------------------------------------------------------------- state ---

// The three networks live behind pointers so a bounded GPU attempt can load its own private
// copies on its worker thread; only a completed attempt moves them into view (see below).
static std::unique_ptr<PromptSlover> prompt_slover;
static std::unique_ptr<DiffusionSlover> diffusion_slover;
static std::unique_ptr<DecodeSlover> decode_slover;
static string loaded_dir;
static bool gpu_instance_created = false;
static bool loaded_on_gpu = false;

// Once a Vulkan attempt failed or timed out in this process, later inits skip it.
static bool gpu_failed = false;

// init() result codes, mirrored by SdNcnn.INIT_* on the Kotlin side.
static const int SD_INIT_ON_GPU = 0;
static const int SD_INIT_ON_CPU = 1;
static const int SD_INIT_GPU_TIMED_OUT = 2;
static const int SD_INIT_FAILED = 3;

// True when this ncnn build has Vulkan compute and the phone exposes a device.
static bool gpu_available()
{
#if NCNN_VULKAN
    return ncnn::get_gpu_count() > 0;
#else
    return false;
#endif
}

// Loads all three networks on one compute path into fresh instances handed out through
// [p]/[df]/[dec]; on any failure leaves them empty again (the already-loaded ones are freed).
static int load_all(const string& d, bool useGpu,
                    std::unique_ptr<PromptSlover>& p,
                    std::unique_ptr<DiffusionSlover>& df,
                    std::unique_ptr<DecodeSlover>& dec)
{
    p = std::make_unique<PromptSlover>();
    df = std::make_unique<DiffusionSlover>();
    dec = std::make_unique<DecodeSlover>();
    if (p->load(d, useGpu) != 0) { p.reset(); df.reset(); dec.reset(); return -1; }
    if (df->load(d, useGpu) != 0) { p.reset(); df.reset(); dec.reset(); return -1; }
    if (dec->load(d, useGpu) != 0) { p.reset(); df.reset(); dec.reset(); return -1; }
    return 0;
}

// Chases the GPU path inside a worker thread for at most [timeoutMs]; the driver's setup can
// hang forever on phones with broken Vulkan and must cost less than the generation itself.
// Returns 1 on success (the three loaded networks were moved into the static singletons),
// 0 when the attempt failed on its own, -1 when the wait timed out (the worker is detached,
// still loading into its private copy — it never touches what the CPU path will load).
static int try_gpu_load(const string& d, int timeoutMs)
{
    struct Attempt
    {
        std::unique_ptr<PromptSlover> p;
        std::unique_ptr<DiffusionSlover> df;
        std::unique_ptr<DecodeSlover> dec;
        int result = -1;
        bool done = false;
        std::mutex m;
        std::condition_variable cv;
    };
    // shared_ptr: a detached worker may outlive this call entirely.
    auto attempt = std::make_shared<Attempt>();

    std::thread worker([attempt, d]() {
        attempt->result = load_all(d, true, attempt->p, attempt->df, attempt->dec);
        {
            std::lock_guard<std::mutex> lk(attempt->m);
            attempt->done = true;
        }
        attempt->cv.notify_one();
        // On the timed-out path the caller went on without us; the private copies are freed
        // right here when the thread ends.
    });

    bool finished = false;
    int wait = timeoutMs < 5000 ? 5000 : timeoutMs;
    {
        std::unique_lock<std::mutex> lk(attempt->m);
        finished = attempt->cv.wait_for(lk, std::chrono::milliseconds(wait),
                                        [&] { return attempt->done; });
    }
    if (finished && attempt->result == 0)
    {
        worker.join();
        prompt_slover = std::move(attempt->p);
        diffusion_slover = std::move(attempt->df);
        decode_slover = std::move(attempt->dec);
        return 1;
    }
    LOG("GPU load %s after %d ms", finished ? "failed" : "timed out", wait);
    worker.detach();
    return finished ? 0 : -1;
}

static void unload_all()
{
    decode_slover.reset();
    diffusion_slover.reset();
    prompt_slover.reset();
    loaded_dir.clear();
}

// --------------------------------------------------------------------------- JNI layer ---

extern "C" {

// Loads the CLIP + UNet + VAE weights from [modelDir] (about 2 GB of RAM once loaded).
// Tries the GPU (Vulkan compute) first when [preferGpu]; the attempt is bounded by
// [gpuTimeoutMs] (the driver can hang) and a missing device, failed load, or timeout there
// falls back to the CPU path of the original engine. Returns one of the SD_INIT_* codes;
// [onGpu()] reports which path won afterwards.
JNIEXPORT jint JNICALL
Java_com_sebialex_agent_sdncnn_SdNcnn_init(JNIEnv* env, jclass, jstring jdir,
                                           jboolean preferGpu, jint gpuTimeoutMs)
{
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    string d = dir ? dir : "";
    env->ReleaseStringUTFChars(jdir, dir);

    // Re-init for another directory starts from scratch (weights do not fit twice).
    if (!loaded_dir.empty()) unload_all();

    bool onGpu = false;
    // -2 = never attempted (no preference, no device, or already failed once here);
    // 1 = loaded, 0 = failed, -1 = timed out (see try_gpu_load).
    int gpu = -2;
    if (preferGpu == JNI_TRUE && !gpu_failed && gpu_available())
    {
        if (ncnn::create_gpu_instance() == 0)
        {
            gpu_instance_created = true;
            gpu = try_gpu_load(d, gpuTimeoutMs);
            if (gpu == 1)
            {
                onGpu = true;
            }
            else
            {
                gpu_failed = true;
                LOG("GPU init %s, falling back to the CPU", gpu == -1 ? "timed out" : "failed");
            }
        }
        else
        {
            LOG("Vulkan instance creation failed, staying on the CPU");
        }
    }
    if (onGpu)
    {
        loaded_on_gpu = true;
        LOG("initialized from %s on the GPU", d.c_str());
        loaded_dir = d;
        return SD_INIT_ON_GPU;
    }

    loaded_on_gpu = false;
    if (gpu_instance_created && gpu != -1)
    {
        // After a plain failure the worker is gone; after a timeout it is still loading from
        // its private copies and may still touch the instance — destroying it here would pull
        // the ground from under that thread, so close() does it later instead.
        ncnn::destroy_gpu_instance();
        gpu_instance_created = false;
    }
    if (load_all(d, false, prompt_slover, diffusion_slover, decode_slover) != 0)
    {
        unload_all();
        return SD_INIT_FAILED;
    }
    LOG("initialized from %s on the CPU%s", d.c_str(),
        gpu == -2 ? "" : (gpu == -1 ? " after a timed-out GPU attempt" : " after a failed GPU attempt"));
    loaded_dir = d;
    return (gpu == -1) ? SD_INIT_GPU_TIMED_OUT : SD_INIT_ON_CPU;
}

JNIEXPORT void JNICALL
Java_com_sebialex_agent_sdncnn_SdNcnn_close(JNIEnv*, jclass)
{
    unload_all();
    loaded_on_gpu = false;
#if NCNN_VULKAN
    if (gpu_instance_created)
    {
        ncnn::destroy_gpu_instance();
        gpu_instance_created = false;
    }
#endif
    LOG("closed");
}

// True while the loaded weights run on the GPU (Vulkan) rather than the CPU.
JNIEXPORT jboolean JNICALL
Java_com_sebialex_agent_sdncnn_SdNcnn_onGpu(JNIEnv*, jclass)
{
    return loaded_on_gpu ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_sebialex_agent_sdncnn_SdNcnn_isLoadedFor(JNIEnv* env, jclass, jstring jdir)
{
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    string d = dir ? dir : "";
    env->ReleaseStringUTFChars(jdir, dir);
    return loaded_dir == d ? JNI_TRUE : JNI_FALSE;
}

/**
 * Runs txt2img: [steps] diffusion steps (per-step progress on [jprogress]'s onStep(I)I, whose
 * return value false abandons the run — MediaPipe has no such need, but ncnn lets us honor
 * Stop), then decodes the latent. Returns the picture as RGBA bytes (4 x width x height, 256
 * x 256), or null when abandoned.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_sebialex_agent_sdncnn_SdNcnn_txt2img(JNIEnv* env, jclass,
                                              jstring jpositive, jstring jnegative,
                                              jint jstep, jint jseed, jobject jprogress)
{
    jmethodID mid = nullptr;
    if (jprogress != nullptr) {
        jclass cls = env->GetObjectClass(jprogress);
        if (cls) mid = env->GetMethodID(cls, "onStep", "(I)Z");
        if (mid == nullptr) {
            LOG("progress callback missing onStep(Z)I — generating without progress");
            env->ExceptionClear();
        }
    }

    const char* p = env->GetStringUTFChars(jpositive, nullptr);
    string positive = p ? p : "";
    env->ReleaseStringUTFChars(jpositive, p);
    const char* n = env->GetStringUTFChars(jnegative, nullptr);
    string negative = n ? n : "";
    env->ReleaseStringUTFChars(jnegative, n);

    if (loaded_dir.empty() || !prompt_slover || !diffusion_slover || !decode_slover) return nullptr;
    int step = jstep < 1 ? 1 : (jstep > 60 ? 60 : jstep);

    ncnn::Mat cond = prompt_slover->get_conditioning(positive);
    ncnn::Mat uncond = prompt_slover->get_conditioning(negative);

    auto progress = [&](int done) -> bool {
        if (mid == nullptr) return true;
        return env->CallBooleanMethod(jprogress, mid, (jint)done) == JNI_TRUE;
    };

    ncnn::Mat sample = diffusion_slover->sampler_txt2img(jseed, step, cond, uncond, mid ? progress : std::function<bool(int)>([](int) { return true; }));
    if (sample.empty()) return nullptr;

    ncnn::Mat x_samples_ddim = decode_slover->decode(sample);
    if (x_samples_ddim.empty() || x_samples_ddim.w <= 0 || x_samples_ddim.h <= 0)
    {
        LOG("decoding produced no pixels");
        return nullptr;
    }

    // [0, 255] RGBA bytes.
    int w = x_samples_ddim.w, h = x_samples_ddim.h;
    vector<unsigned char> pixels;
    pixels.resize((size_t)w * h * 4);
    x_samples_ddim.to_pixels(pixels.data(), ncnn::Mat::PIXEL_RGBA);

    jbyteArray out = env->NewByteArray((jsize)w * h * 4);
    env->SetByteArrayRegion(out, 0, (jsize)w * h * 4, (const jbyte*)pixels.data());
    return out;
}

JNIEXPORT jint JNICALL
Java_com_sebialex_agent_sdncnn_SdNcnn_imageWidth(JNIEnv*, jclass)
{
    return 256;
}

} // extern "C"
