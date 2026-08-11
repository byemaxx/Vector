package org.matrix.vector.daemon.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineHookProcessPolicyTest {
  @Test
  fun matchesMainAndComponentProcessesForTheExpectedUid() {
    assertTrue(
        InlineHookProcessPolicy.matchesPackage(
            expectedUid = 10123,
            actualUid = 10123,
            processName = "com.example.app",
            applicationProcessName = "com.example.app",
            componentProcesses = setOf("com.example.app:worker")))
    assertTrue(
        InlineHookProcessPolicy.matchesPackage(
            expectedUid = 10123,
            actualUid = 10123,
            processName = "com.example.app:worker",
            applicationProcessName = "com.example.app",
            componentProcesses = setOf("com.example.app:worker")))
  }

  @Test
  fun rejectsWrongUidAndUnrelatedProcesses() {
    assertFalse(
        InlineHookProcessPolicy.matchesPackage(
            expectedUid = 10123,
            actualUid = 11123,
            processName = "com.example.app",
            applicationProcessName = "com.example.app",
            componentProcesses = emptySet()))
    assertFalse(
        InlineHookProcessPolicy.matchesPackage(
            expectedUid = 10123,
            actualUid = 10123,
            processName = "com.other.process",
            applicationProcessName = "com.example.app",
            componentProcesses = setOf("com.example.app:worker")))
  }

  @Test
  fun systemUiVirtualPackageOnlyMatchesItsExactSystemUidProcess() {
    val configured = setOf(SYSTEM_UI_VIRTUAL_PACKAGE)
    assertTrue(
        InlineHookProcessPolicy.matchesSystemUiVirtualPackage(
            configured, SYSTEM_UI_PROCESS, 1000))
    assertFalse(
        InlineHookProcessPolicy.matchesSystemUiVirtualPackage(configured, "system", 1000))
    assertFalse(
        InlineHookProcessPolicy.matchesSystemUiVirtualPackage(
            configured, SYSTEM_UI_PROCESS, 101000))
    assertFalse(
        InlineHookProcessPolicy.matchesSystemUiVirtualPackage(
            emptySet(), SYSTEM_UI_PROCESS, 1000))
  }

  @Test
  fun neverInvalidatesSystemServerButAllowsOtherSystemUidProcesses() {
    assertFalse(InlineHookProcessPolicy.mayInvalidate("system", 1000))
    assertTrue(InlineHookProcessPolicy.mayInvalidate("system:ui", 1000))
    assertTrue(InlineHookProcessPolicy.mayInvalidate("com.vendor.system", 1000))
    assertTrue(InlineHookProcessPolicy.mayInvalidate("system", 101000))
  }
}
