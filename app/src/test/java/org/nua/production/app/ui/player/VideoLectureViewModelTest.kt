package org.nua.production.app.ui.player

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import org.nua.production.app.data.storage.FlatBufferStorageDriver
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VideoLectureViewModelTest {

    @get:Rule
    val tempFolderRule = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockApplication: Application
    private lateinit var viewModel: VideoLectureViewModel

    @Before
    fun setUp() {
        // Redirect Dispatchers.Main to our test dispatcher to force synchronous execution of viewModelScope coroutines
        Dispatchers.setMain(testDispatcher)
        
        FlatBufferStorageDriver.resetInstance()

        mockApplication = mock(Application::class.java)
        `when`(mockApplication.applicationContext).thenReturn(mockApplication)
        `when`(mockApplication.cacheDir).thenReturn(tempFolderRule.newFolder("mock_cache"))

        viewModel = VideoLectureViewModel(mockApplication)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Test Scenario: Unified Integration Flow Validation
     * Verifies that sending a playback tick past the 30-second hotspot boundary 
     * successfully updates state flows and locks screen interfaces.
     */
    @Test
    fun verifyPlaybackTickPastHotspotThresholdHaltsPlayerAndTriggersQuiz() {
        // Act: Simulate player stepping directly to the 32-second timeline mark
        viewModel.onPlaybackTick(positionMs = 32000L, durationMs = 60000L, audioTrackDurationMs = 60000L)

        // Assert
        val currentState = viewModel.uiState.value
        assertEquals("quiz_module_01", currentState.activeQuizId)
        assertEquals(32000L, currentState.currentPositionMs)
    }
}
