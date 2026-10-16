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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import app.androidrik.ai.provider.ImageEditParams
import app.androidrik.ai.provider.ImageGenerationParams
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.ui.ImageGenSize
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.common.android.appTempFolder
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.db.entity.GenMediaEntity
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.image.GeneratedImage
import app.androidrik.rikkahub.data.image.ImageGenerationRunner
import app.androidrik.rikkahub.data.image.toGeneratedImage
import app.androidrik.rikkahub.data.image.toFileNamePart
import app.androidrik.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
    /** 落盘 + 落库的**共用写路径**（与会话内生图工具共用，见 data/image/ImageGenerationRunner） */
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

    fun generateImage() {
        if(prompt.value.isBlank()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val requestPrompt = _prompt.value
                val params = ImageGenerationParams(
                    model = model,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value,
                    size = _size.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerManager.getProviderByType(provider)
                    .generateImage(provider, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                )
            } catch (e: Exception) {
                if(e is CancellationException) return@launch
                Log.e(TAG, "Failed to generate image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val requestPrompt = _prompt.value
                val sourceImages = _referenceImages.value
                val params = ImageEditParams(
                    model = model,
                    prompt = requestPrompt,
                    images = sourceImages,
                    numOfImages = _numberOfImages.value,
                    size = _size.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerManager.getProviderByType(provider)
                    .editImage(provider, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                    type = GenMediaEntity.TYPE_IMAGE_EDIT,
                    sourcePaths = sourceImages.joinToString("\n"),
                )
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to edit image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun collectImageGeneration(
        images: Flow<ImageGenerationItem>,
        prompt: String,
        modelName: String,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ) {
        val finalImages = mutableListOf<GeneratedImage>()
        var previewFile: File? = null
        var finalIndex = 0

        images.collect { item ->
            if (item.partial) {
                previewFile?.delete()
                val imageFile = saveImagePreview(
                    item = item,
                    modelName = modelName,
                    index = item.partialImageIndex ?: finalIndex,
                )
                previewFile = imageFile
                _currentGeneratedImages.value = finalImages + GeneratedImage(
                    id = 0,
                    prompt = prompt,
                    filePath = imageFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    model = modelName
                )
            } else {
                previewFile?.delete()
                previewFile = null
                val imageFile = imageGenerationRunner.saveImageToStorage(
                    item = item,
                    prompt = prompt,
                    modelName = modelName,
                    index = finalIndex,
                    type = type,
                    sourcePaths = sourcePaths,
                )
                finalImages.add(
                    GeneratedImage(
                        id = 0, // Will be updated after database insertion
                        prompt = prompt,
                        filePath = imageFile.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        model = modelName
                    )
                )
                finalIndex++
                _currentGeneratedImages.value = finalImages.toList()
            }
        }
    }

    private fun saveImagePreview(
        item: ImageGenerationItem,
        modelName: String,
        index: Int,
    ): File {
        val timestamp = System.currentTimeMillis()
        val imageFile = File(getApplication<Application>().appTempFolder, "imggen_${timestamp}_${modelName.toFileNamePart()}_$index.png")
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
