package org.nua.production.app.data.storage

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File

class FlatBufferStorageDriverTest {

    @get:Rule
    val tempFolderRule = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var storageDriver: FlatBufferStorageDriver

    @Before
    fun setUp() {
        // Reset the singleton instance to prevent cached context/directory leakage between test runs
        FlatBufferStorageDriver.resetInstance()

        mockContext = mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        
        // Divert internal storage allocations directly into JUnit's safe temporary sandbox filesystem
        `when`(mockContext.cacheDir).thenReturn(tempFolderRule.newFolder("mock_cache"))

        storageDriver = FlatBufferStorageDriver.getInstance(mockContext)
    }

    /**
     * Test Scenario 1: Standalone Boundary Serialization Verification
     * Asserts that data blocks write out as independent files ending with our verified .tlm extension.
     */
    @Test
    fun verifyRecordWritesToIsolatedFileBoundary() {
        val createdFile = storageDriver.writeRecord("math_module_01", 85, 4L)

        assertNotNull("Storage driver returned a null file reference during execution pass.", createdFile)
        assertTrue("Output asset failed to construct on the physical storage device.", createdFile!!.exists())
        assertEquals("tlm", createdFile.extension)
        assertTrue("Filename pattern missed serialization schema naming constraints.", createdFile.name.contains("v4"))
    }

    /**
     * Test Scenario 2: Structural Data Containment Isolation
     * Verifies that sequential mutations dump onto separate distinct files instead of overwriting a shared space.
     */
    @Test
    fun verifyConsecutiveMutationsGenerateSeparatePhysicalAssets() {
        val firstFile = storageDriver.writeRecord("history_module_02", 20, 1L)
        val secondFile = storageDriver.writeRecord("history_module_02", 40, 2L)

        assertNotNull(firstFile)
        assertNotNull(secondFile)
        assertNotEquals("Data boundary collision! Sequential iterations thrashed the same file location.", firstFile!!.absolutePath, secondFile!!.absolutePath)
        
        val activeFilesCount = storageDriver.getRecordDirectory().listFiles()?.filter { it.extension == "tlm" }?.size ?: 0
        assertEquals("Filesystem count tracker misaligned with isolated storage bounds.", 2, activeFilesCount)
    }
}
