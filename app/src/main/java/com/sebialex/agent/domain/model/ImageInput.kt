package com.sebialex.agent.domain.model

/**
 * An image prepared for a vision model: [base64] bytes of [mime] (JPEG or PNG), already scaled
 * down. [label] says where it came from, e.g. "/imports/photo.jpg" or "/imports/clip.mp4 at 0:04".
 */
data class ImageInput(val label: String, val mime: String, val base64: String) {
    val dataUrl: String get() = "data:$mime;base64,$base64"
}
