package com.sebialex.agent

import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.permission.PermissionDecision
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.permission.RiskReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private val policy = PermissionPolicy()

    @Test fun safeCommandsAreAllowed() {
        assertEquals(PermissionDecision.Allow, policy.evaluateShell("ls -la"))
        assertEquals(PermissionDecision.Allow, policy.evaluateShell("cat notes/a.md | grep plan"))
        assertEquals(PermissionDecision.Allow, policy.evaluateShell("python make_plan.py"))
    }

    @Test fun destructiveCommandsAsk() {
        val d = policy.evaluateShell("ls && rm -r tmp")
        assertTrue(d is PermissionDecision.Ask && d.reason == RiskReason.DESTRUCTIVE)
    }

    @Test fun installsAsk() {
        val d = policy.evaluateShell("pip install requests")
        assertTrue(d is PermissionDecision.Ask && d.reason == RiskReason.PACKAGE_INSTALL)
    }

    @Test fun sudoIsDenied() {
        assertTrue(policy.evaluateShell("sudo ls") is PermissionDecision.Deny)
    }

    @Test fun writesOutsideWorkspaceAsk() {
        val d = policy.evaluate(ToolCall.WriteFile("1", "../../etc/hosts", "x"))
        assertTrue(d is PermissionDecision.Ask && d.reason == RiskReason.OUTSIDE_WORKSPACE)
        assertEquals(PermissionDecision.Allow, policy.evaluate(ToolCall.WriteFile("2", "notes/plan.md", "x")))
    }
}
