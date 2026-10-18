package com.rawlens.srpipeline.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the sr-pipeline CLI argument contract.
 *
 * NOTE (scope): [Main.kt](/Users/monikamalinowska/AndroidStudioProjects/RawLens/tools/sr-pipeline/src/main/kotlin/com/rawlens/srpipeline/cli/Main.kt)
 * declares its arg parser (`parseArgs`/`parseCrop`) as file-`private` and
 * reports errors via `exitProcess(2)`, so it cannot be called directly from
 * a unit test. These tests therefore exercise a tiny parser defined below in
 * test scope that mirrors the documented CLI contract (flags, defaults, and
 * validation rules from the `Main.kt` usage text). If the real parser is
 * ever made testable (e.g. returning a sealed result instead of exiting),
 * these tests should be re-pointed at it.
 */
internal data class TestCrop(val x: Int, val y: Int, val w: Int, val h: Int)

internal data class TestPlan(
    val inDir: String,
    val outDir: String,
    val ref: Int = 0,
    val crop: TestCrop? = null,
    val limit: Int? = null,
    val backend: String,
    val heapBudgetMb: Int? = null,
)

internal fun parseTestArgs(args: Array<String>): TestPlan {
    var inDir: String? = null
    var outDir: String? = null
    var ref = 0
    var crop: TestCrop? = null
    var limit: Int? = null
    var backend: String? = null
    var heapBudgetMb: Int? = null
    var i = 0
    fun err(message: String): Nothing = throw IllegalArgumentException(message)
    while (i < args.size) {
        when (args[i]) {
            "--in" -> { inDir = args.getOrNull(++i) ?: err("--in requires a DIR value"); i++ }
            "--out" -> { outDir = args.getOrNull(++i) ?: err("--out requires a DIR value"); i++ }
            "--ref" -> {
                val raw = args.getOrNull(++i) ?: err("--ref requires an N value")
                ref = raw.toIntOrNull() ?: err("--ref must be an integer >= 0")
                if (ref < 0) err("--ref must be >= 0")
                i++
            }
            "--crop" -> {
                val raw = args.getOrNull(++i) ?: err("--crop requires an x,y,w,h value")
                val nums = raw.split(",").map { it.trim().toIntOrNull() ?: err("--crop values must be integers") }
                if (nums.size != 4) err("--crop must be x,y,w,h")
                if (nums[0] < 0 || nums[1] < 0) err("--crop x and y must be >= 0")
                if (nums[2] <= 0 || nums[3] <= 0) err("--crop w and h must be > 0")
                crop = TestCrop(nums[0], nums[1], nums[2], nums[3])
                i++
            }
            "--limit" -> {
                val raw = args.getOrNull(++i) ?: err("--limit requires an N value")
                val n = raw.toIntOrNull() ?: err("--limit must be an integer > 0")
                if (n <= 0) err("--limit must be > 0")
                limit = n
                i++
            }
            "--backend" -> { backend = args.getOrNull(++i) ?: err("--backend requires cpu|vulkan"); i++ }
            "--heap-budget-mb" -> {
                val raw = args.getOrNull(++i) ?: err("--heap-budget-mb requires an N value")
                val n = raw.toIntOrNull() ?: err("--heap-budget-mb must be an integer > 0")
                if (n <= 0) err("--heap-budget-mb must be > 0")
                heapBudgetMb = n
                i++
            }
            else -> err("unknown argument \"${args[i]}\"")
        }
    }
    if (inDir.isNullOrBlank()) err("--in DIR is required")
    if (outDir.isNullOrBlank()) err("--out DIR is required")
    val b = backend ?: err("--backend cpu|vulkan is required")
    if (b != "cpu" && b != "vulkan") err("--backend must be cpu or vulkan")
    return TestPlan(inDir!!, outDir!!, ref, crop, limit, b, heapBudgetMb)
}

class CliArgsTest {

    @Test
    fun `required flags with defaults parse`() {
        val plan = parseTestArgs(arrayOf("--in", "/tmp/burst", "--out", "/tmp/sr-out", "--backend", "cpu"))
        assertEquals("/tmp/burst", plan.inDir)
        assertEquals("/tmp/sr-out", plan.outDir)
        assertEquals("cpu", plan.backend)
        assertEquals(0, plan.ref)
        assertNull(plan.crop)
        assertNull(plan.limit)
    }

    @Test
    fun `all optional flags parse`() {
        val plan = parseTestArgs(
            arrayOf(
                "--in", "a", "--out", "b", "--ref", "2",
                "--crop", "1,2,64,32", "--limit", "8", "--backend", "vulkan",
            ),
        )
        assertEquals(2, plan.ref)
        assertEquals(TestCrop(1, 2, 64, 32), plan.crop)
        assertEquals(8, plan.limit as Int)
        assertEquals("vulkan", plan.backend)
    }

    @Test
    fun `unknown backend is rejected`() {
        try {
            parseTestArgs(arrayOf("--in", "a", "--out", "b", "--backend", "metal"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("--backend must be cpu or vulkan", e.message)
        }
    }

    @Test
    fun `negative ref is rejected`() {
        try {
            parseTestArgs(arrayOf("--in", "a", "--out", "b", "--ref", "-1", "--backend", "cpu"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("--ref must be >= 0", e.message)
        }
    }

    @Test
    fun `heap budget flag parses`() {
        val plan = parseTestArgs(
            arrayOf("--in", "a", "--out", "b", "--backend", "cpu", "--heap-budget-mb", "450"),
        )
        assertEquals(450, plan.heapBudgetMb as Int)
    }

    @Test
    fun `heap budget flag defaults to null`() {
        val plan = parseTestArgs(arrayOf("--in", "a", "--out", "b", "--backend", "cpu"))
        assertNull(plan.heapBudgetMb)
    }

    @Test
    fun `non-positive heap budget is rejected`() {
        try {
            parseTestArgs(
                arrayOf("--in", "a", "--out", "b", "--backend", "cpu", "--heap-budget-mb", "0"),
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("--heap-budget-mb must be > 0", e.message)
        }
    }

    @Test
    fun `missing required flags are rejected`() {
        try {
            parseTestArgs(arrayOf("--in", "a", "--backend", "cpu"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("--out DIR is required", e.message)
        }
    }
}
