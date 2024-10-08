package com.example.diaryapp.data.repository

import com.example.diaryapp.model.Diary
import com.example.diaryapp.util.Constants.APP_ID
import com.example.diaryapp.model.RequestState
import com.example.diaryapp.util.toInstant
import io.realm.kotlin.Realm
import io.realm.kotlin.delete
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.sync.SyncConfiguration
import io.realm.kotlin.query.Sort
import io.realm.kotlin.types.RealmInstant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.mongodb.kbson.ObjectId
import java.time.ZoneId
import java.time.ZonedDateTime

object MongoDB: MongoRepository {

    private val app = App.create(APP_ID)
    private val user = app.currentUser
    private lateinit var realm: Realm

    init {
        configureTheRealm()
    }

    override fun configureTheRealm() {
        if (user != null) {
            val config = SyncConfiguration.Builder(user, setOf(Diary::class))
                .initialSubscriptions { sub ->
                    add(
                        query = sub.query<Diary>(query = "ownerId == $0", user.id),
                        name = "User's Diaries"
                    )
                }
//                .log(LogLevel.ALL)
                .build()
            realm = Realm.open(config)
        }
    }

    override fun getAllDiaries(): Flow<Diaries> {
        return if (user != null) {
            try {
                realm.query<Diary>(query = "ownerId == $0", user.id)
                    .sort(property = "date", sortOrder = Sort.DESCENDING)
                    .asFlow()
                    .map { result ->
                        RequestState.Success(
                            data = result.list.groupBy {
                                it.date.toInstant()
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                            }
                        )
                    }
            } catch (e: Exception) {
                flow { emit(RequestState.Error(error = e)) }
            }
        } else {
            flow { emit(RequestState.Error(error = UserNotAuthenticatedException())) }
        }
    }

    override fun getSelectedDiary(diaryId: ObjectId): Flow<RequestState<Diary>> {
       return if(user != null){
            try {
                realm.query<Diary>(query = "_id == $0", diaryId).asFlow().map {
                    RequestState.Success(data = it.list.first())
                }
            }catch (e: Exception){
                flow { emit(RequestState.Error(error = e)) }
            }
        }else{
            flow { emit(RequestState.Error(error = UserNotAuthenticatedException())) }
        }
    }

    override suspend fun insertDiary(diary: Diary): RequestState<Diary> {
        return if(user != null){
           realm.write {
               try {
                   val addedDiary = copyToRealm(diary.apply { ownerId = user.id })
                   RequestState.Success(data = addedDiary)
               }catch (e: Exception){
                   RequestState.Error(error = e)
               }
           }
        }else{
            RequestState.Error(error = UserNotAuthenticatedException())
        }
    }

    override suspend fun updateDiary(diary: Diary): RequestState<Diary> {
        return if(user != null){
            realm.write {
                try {
                    val queryDiary = query<Diary>(query = "_id == $0", diary._id).first().find()
                    if(queryDiary != null){
                        queryDiary.title = diary.title
                        queryDiary.description = diary.description
                        queryDiary.mood = diary.mood
                        queryDiary.images = diary.images
                        queryDiary.date = diary.date
                        RequestState.Success(data = queryDiary)
                    }else{
                        RequestState.Error(error =  Exception("Queried Diary does not ecist."))
                    }
                }catch (e: Exception){
                    RequestState.Error(error = e)
                }
            }
        }else{
            RequestState.Error(error = UserNotAuthenticatedException())
        }
    }

    override suspend fun deleteDiary(id: ObjectId): RequestState<Diary> {
        return if(user != null){
            realm.write {
                val diary = query<Diary>(query = "_id == $0 AND ownerId == $1", id, user.id).first().find()
                if(diary != null){
                    try {
                        delete(diary)
                        RequestState.Success(data = diary)
                    }catch (e: Exception){
                        RequestState.Error(error = e)
                    }
                }else{
                    RequestState.Error(error = Exception("Diary does not exist."))
                }
            }
        }else{
            RequestState.Error(error = UserNotAuthenticatedException())
        }
    }

    override suspend fun deleteAllDiaries(): RequestState<Boolean> {
        return if(user != null){
            realm.write {
                val diaries = this.query<Diary>(query = "ownerId == $0", user.id).find()
                try {
                    delete(diaries)
                    RequestState.Success(data = true)
                }catch (e: Exception){
                    RequestState.Error(error = e)
                }
            }
        }else{
            RequestState.Error(error = UserNotAuthenticatedException())
        }
    }

    override suspend fun getFilteredDiaries(zonedDateTime: ZonedDateTime): Flow<Diaries> {
        return if (user != null) {
            try {
                realm.query<Diary>(query = "ownerId == $0 AND date < $1 AND date > $2", user.id,
                    RealmInstant.from(zonedDateTime.plusDays(1).toInstant().epochSecond, 0),
                    RealmInstant.from(zonedDateTime.minusDays(1).toInstant().epochSecond, 0)
                ).asFlow().map { result ->
                    RequestState.Success(data = result.list.groupBy {
                        it.date.toInstant()
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    })
                }
            } catch (e: Exception) {
                flow { emit(RequestState.Error(error = e)) }
            }
        } else {
            flow { emit(RequestState.Error(error = UserNotAuthenticatedException())) }
        }
    }

}

private class UserNotAuthenticatedException: Exception("User is not Logged in.")