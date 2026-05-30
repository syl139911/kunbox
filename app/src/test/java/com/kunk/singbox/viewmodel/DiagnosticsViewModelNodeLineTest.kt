package com.kunk.singbox.viewmodel

import com.kunk.singbox.model.NodeUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsViewModelNodeLineTest {

    @Test
    fun resolveNodeLineTarget_prefersRuntimeActiveLabelNode() {
        val runtimeNode = createNode(id = "runtime", name = "runtime-node")
        val storedNode = createNode(id = "stored", name = "stored-node")
        val fallbackNode = createNode(id = "fallback", name = "fallback-node")

        val result = resolveNodeLineTarget(runtimeNode, storedNode, fallbackNode)

        assertEquals("runtime", result?.node?.id)
        assertEquals("运行态 activeLabel", result?.sourceLabel)
    }

    @Test
    fun resolveNodeLineTarget_usesStoredActiveLabelWhenRuntimeMissing() {
        val storedNode = createNode(id = "stored", name = "stored-node")
        val fallbackNode = createNode(id = "fallback", name = "fallback-node")

        val result = resolveNodeLineTarget(
            activeLabelNode = null,
            storedActiveLabelNode = storedNode,
            fallbackNode = fallbackNode
        )

        assertEquals("stored", result?.node?.id)
        assertEquals("状态存储 activeLabel", result?.sourceLabel)
    }

    @Test
    fun resolveNodeLineTarget_fallsBackToSelectedNode() {
        val fallbackNode = createNode(id = "fallback", name = "fallback-node")

        val result = resolveNodeLineTarget(
            activeLabelNode = null,
            storedActiveLabelNode = null,
            fallbackNode = fallbackNode
        )

        assertEquals("fallback", result?.node?.id)
        assertEquals("当前选中节点", result?.sourceLabel)
    }

    @Test
    fun resolveNodeLineTarget_returnsNullWhenNoSignalAvailable() {
        val result = resolveNodeLineTarget(
            activeLabelNode = null,
            storedActiveLabelNode = null,
            fallbackNode = null
        )

        assertNull(result)
    }

    private fun createNode(id: String, name: String): NodeUi {
        return NodeUi(
            id = id,
            name = name,
            protocol = "vmess",
            group = "test",
            sourceProfileId = "profile-1"
        )
    }
}
