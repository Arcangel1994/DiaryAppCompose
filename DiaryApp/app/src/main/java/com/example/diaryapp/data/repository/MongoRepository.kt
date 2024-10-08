package com.example.diaryapp.data.repository

import com.example.diaryapp.model.Diary
import com.example.diaryapp.model.RequestState
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import org.mongodb.kbson.ObjectId
import java.time.ZonedDateTime

typealias Diaries = RequestState<Map<LocalDate, List<Diary>>>

interface MongoRepository {

    fun configureTheRealm()
    fun getAllDiaries(): Flow<Diaries>
    fun getSelectedDiary(diaryId: ObjectId): Flow<RequestState<Diary>>
    suspend fun insertDiary(diary: Diary): RequestState<Diary>
    suspend fun updateDiary(diary: Diary): RequestState<Diary>
    suspend fun deleteDiary(id: ObjectId): RequestState<Diary>
    suspend fun deleteAllDiaries(): RequestState<Boolean>
    suspend fun getFilteredDiaries(zonedDateTime: ZonedDateTime): Flow<Diaries>

}