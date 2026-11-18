package app.androidrik.rikkahub.ui.pages.imggen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.ai.ui.ImageGenSize
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.common.android.appTempFolder
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.image.GeneratedImage
import app.androidrik.rikkahub.data.image.ImageGenerationRunner
import app.androidrik.rikkahub.data.image.ImageGenerationUpdate
import app.androidrik.rikkahub.data.image.toGeneratedImage
import app.androidrik.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    private val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
    private val imageGenerationRunner: ImageGenerationRunner,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _size = MutableStateFlow(ImageGenSize.AUTO.value)
    val size: StateFlow<String> = _size

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateSize(size: String) {
        _size.value = size
    }

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
    }

    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
    }

    fun clearError() {
        _error.value = null
    }

    fun startNewSession() {
        cancelJob?.cancel()
        clearReferenceImages()
        _prompt.value = ""
        _currentGeneratedImages.value = emptyList()
        _error.value = null
        _isGenerating.value = false
    }

    fun generateImage() = startImageRequest(emptyList())

    fun editImage() {
        val sources = referenceImages.value
        if (sources.isNotEmpty()) startImageRequest(sources)
    }

    private fun startImageRequest(sourceImages: List<String>) {
        val requestPrompt = prompt.value
        if (requestPrompt.isBlank()) return
        val request = MediaGenerationRequest(
            prompt = requestPrompt,
            inputs = sourceImages.map { MediaGenerationInput.Image(url = it) },
            count = numberOfImages.value,
            resolution = size.value,
        )
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                collectImageGeneration(imageGenerationRunner.generate(settings, request), requestPrompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image request failed", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                if (cancelJob === coroutineContext.job) {
                    _isGenerating.value = false
                    cancelJob = null
                }
            }
        }
        cancelJob?.start()
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun collectImageGeneration(
        updates: Flow<ImageGenerationUpdate>,
        prompt: String,
    ) {
        val finalImages = mutableListOf<GeneratedImage>()
        var previewFile: File? = null
        try {
            updates.collect { update ->
                previewFile?.delete()
                when (update) {
                    is ImageGenerationUpdate.Preview -> {
                        val imageFile = saveImagePreview(update.item)
                        previewFile = imageFile
                        _currentGeneratedImages.value = finalImages + GeneratedImage(
                            id = 0,
                            prompt = prompt,
                            filePath = imageFile.absolutePath,
                            timestamp = System.currentTimeMillis(),
                            model = update.modelName,
                        )
                    }
                    is ImageGenerationUpdate.Saved -> {
                        previewFile = null
                        finalImages += update.image
                        _currentGeneratedImages.value = finalImages.toList()
                    }
                }
            }
        } finally {
            previewFile?.let { file ->
                file.delete()
                _currentGeneratedImages.update { images -> images.filterNot { it.filePath == file.absolutePath } }
            }
        }
    }

    private fun saveImagePreview(item: ImageGenerationItem): File {
        val imageFile = File(
            getApplication<Application>().appTempFolder,
            "imggen_${Uuid.random()}.png",
        )
        return filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                // Delete from database first
                genMediaRepository.deleteMedia(image.id)

                // Then delete the file
                val file = File(image.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
                _error.value = "Failed to delete image"
            }
        }
    }

    /**
     * 批量删除（对齐上游 3896b6d6 长按多选）：返回删除失败的图片以便 UI 保留/提示。
     * 删除失败的图片 = 文件删除失败或 DB 删除失败。
     */
    suspend fun deleteImages(images: List<GeneratedImage>): List<GeneratedImage> =
        withContext(Dispatchers.IO) {
            images.filter { image ->
                try {
                    val file = File(image.filePath)
                    check(!file.exists() || file.delete()) { "Failed to delete image file" }
                    genMediaRepository.deleteMedia(image.id)
                    false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete image ${image.id}", e)
                    true
                }
            }
        }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
    }
}
