package com.example.diaryapp.presentation.screens.write

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.diaryapp.data.database.dao.ImageToDeleteDao
import com.example.diaryapp.data.database.dao.ImagesToUploadDao
import com.example.diaryapp.data.database.entity.ImageToDelete
import com.example.diaryapp.data.database.entity.ImageToUpload
import com.example.diaryapp.data.repository.MongoDB
import com.example.diaryapp.model.Diary
import com.example.diaryapp.model.GalleryImage
import com.example.diaryapp.model.GalleryState
import com.example.diaryapp.model.Mood
import com.example.diaryapp.util.Constants.WRITE_SCREEN_ARGUMENT_KEY
import com.example.diaryapp.model.RequestState
import com.example.diaryapp.util.fetchImagesFromFirebase
import com.example.diaryapp.util.toRealmInstant
import com.google.firebase.ktx.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import io.realm.kotlin.types.RealmInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mongodb.kbson.ObjectId
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class WriteViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val imagesToUploadDao: ImagesToUploadDao,
    private val imageToDeleteDao: ImageToDeleteDao
): ViewModel() {

    val galleryState = GalleryState()

    var uiState: UiState by mutableStateOf(UiState())
        private set

    init {
        getDiaryIdArgument()
        fetchSelectedDiary()
    }

    private fun getDiaryIdArgument(){
        uiState = uiState.copy(
            selectedDiaryId = savedStateHandle.get<String>(
                key = WRITE_SCREEN_ARGUMENT_KEY
            )
        )
    }

    private fun fetchSelectedDiary(){
        if(uiState.selectedDiaryId != null){
            viewModelScope.launch(Dispatchers.Main) {
                MongoDB.getSelectedDiary(diaryId = ObjectId.invoke(uiState.selectedDiaryId!!))
                    .catch { RequestState.Error(error = Exception("Diary is already delete.")) }
                    .collect{ diary ->
                        if(diary is RequestState.Success){
                            setSelectedDiary(diary = diary.data)
                            setTitle(title = diary.data.title)
                            setDescription(description = diary.data.description)
                            setMoon(moon = Mood.valueOf(diary.data.mood))

                            fetchImagesFromFirebase(
                                remoteImagePaths = diary.data.images,
                                onImageDownload = { downloadedImage ->
                                    galleryState.addImage(
                                        GalleryImage(
                                            image = downloadedImage,
                                            remoteImagePath = extractImagePath(
                                                remotePath = downloadedImage.toString()
                                            )
                                        )
                                    )
                                }
                            )

                        }
                    }
            }
        }
    }

    private fun setSelectedDiary(diary: Diary){
        uiState = uiState.copy(selectedDiary = diary)
    }

    fun setTitle(title: String){
        uiState = uiState.copy(title = title)
    }

    fun setDescription(description: String){
        uiState = uiState.copy(description = description)
    }

    private fun setMoon(moon: Mood){
        uiState = uiState.copy(mood = moon)
    }

    fun updateDateTime(zonedDateTime: ZonedDateTime){
        uiState = uiState.copy(updatedDateTime = zonedDateTime.toInstant().toRealmInstant())
    }

    fun upsertDiary(
        diary: Diary,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ){
        viewModelScope.launch(Dispatchers.IO) {
            if(uiState.selectedDiaryId != null){
                updateDiary(
                    diary = diary,
                    onSuccess = onSuccess,
                    onError = onError
                )
            }else{
                insertDiary(
                    diary = diary,
                    onSuccess = onSuccess,
                    onError = onError
                )
            }
        }
    }

    private suspend fun insertDiary(
        diary: Diary,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ){
        val result = MongoDB.insertDiary(diary = diary.apply {
            if(uiState.updatedDateTime != null){
                date = uiState.updatedDateTime!!
            }
        })
        if(result is RequestState.Success){
            uploadImagesToFirebase()
            withContext(Dispatchers.Main){
                onSuccess()
            }
        }else if (result is RequestState.Error){
            withContext(Dispatchers.Main){
                onError(result.error.message.toString())
            }
        }
    }

    private suspend fun updateDiary(
        diary: Diary,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ){
        val result = MongoDB.updateDiary(
            diary = diary.apply {
                _id = ObjectId.invoke(uiState.selectedDiaryId!!)
                date = if(uiState.updatedDateTime != null){
                    uiState.updatedDateTime!!
                }else{
                    uiState.selectedDiary!!.date
                }
            }
        )
        if(result is RequestState.Success){
            uploadImagesToFirebase()

            deleteImagesFromFirebase()

            withContext(Dispatchers.Main){
                onSuccess()
            }
        }else if(result is RequestState.Error){
            withContext(Dispatchers.Main){
                onError(result.error.message.toString())
            }
        }
    }

    fun deleteDiary(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ){
        viewModelScope.launch(Dispatchers.IO) {
            if(uiState.selectedDiaryId != null){
                val result = MongoDB.deleteDiary(id = ObjectId.invoke(uiState.selectedDiaryId!!))
                if(result is RequestState.Success){
                    withContext(Dispatchers.Main){
                        uiState.selectedDiary?.let { deleteImagesFromFirebase(images = it.images) }
                        onSuccess()
                    }
                }else if(result is RequestState.Error){
                    withContext(Dispatchers.Main){
                        onError(result.error.message.toString())
                    }
                }
            }
        }
    }

    fun addImage(image: Uri, imageType: String){
        val remoteImagepath = "images/${FirebaseAuth.getInstance().currentUser?.uid}/" +
                "${image.lastPathSegment}-${System.currentTimeMillis()}.$imageType"
        Log.d("WriteViewModel", remoteImagepath)
        galleryState.addImage(
            GalleryImage(
                image = image,
                remoteImagePath = remoteImagepath
            )
        )
    }

    private fun uploadImagesToFirebase(){
        val storage = FirebaseStorage.getInstance().reference
        galleryState.images.forEach { galleryImage ->
            val imagePath = storage.child(galleryImage.remoteImagePath)
            imagePath.putFile(galleryImage.image)
                .addOnProgressListener {
                    val sessionUri = it.uploadSessionUri
                    if(sessionUri != null){
                        viewModelScope.launch(Dispatchers.IO) {
                            imagesToUploadDao.addImageToUpload(
                                ImageToUpload(
                                    remoteImagePath = galleryImage.remoteImagePath,
                                    imageUri = galleryImage.image.toString(),
                                    sessionUrl = sessionUri.toString()
                                )
                            )
                        }
                    }
                }
        }
    }

    private fun deleteImagesFromFirebase(images: List<String>? = null){
        val storage = FirebaseStorage.getInstance().reference
        if (images != null){
            images.forEach { remotePath ->
                storage.child(remotePath).delete()
                    .addOnFailureListener {
                        viewModelScope.launch(Dispatchers.IO) {
                            imageToDeleteDao.addImageToDelete(
                                ImageToDelete(remoteImagePath = remotePath)
                            )
                        }
                    }
            }
        }else{
            galleryState.imagesToBeDeleted.map { it.remoteImagePath }.forEach { remotePath ->
                storage.child(remotePath).delete()
                    .addOnFailureListener {
                        viewModelScope.launch(Dispatchers.IO) {
                            imageToDeleteDao.addImageToDelete(
                                ImageToDelete(remoteImagePath = remotePath)
                            )
                        }
                    }
            }
        }
    }

    private fun extractImagePath(remotePath: String): String{
        val chunks = remotePath.split("%2F")
        val imageName = chunks[2].split("?").first()
        return "images/${Firebase.auth.currentUser?.uid}/$imageName"
    }

}

data class UiState(
    val selectedDiaryId: String? = null,
    val selectedDiary: Diary? = null,
    val title: String = "",
    val description: String = "",
    val mood: Mood = Mood.Neutral,
    val updatedDateTime: RealmInstant? = null
)