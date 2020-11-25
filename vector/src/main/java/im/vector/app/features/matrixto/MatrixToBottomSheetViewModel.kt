/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.matrixto

import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.raw.wellknown.getElementWellknown
import im.vector.app.features.raw.wellknown.isE2EByDefault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.internal.util.awaitCallback

class MatrixToBottomSheetViewModel @AssistedInject constructor(
        @Assisted initialState: MatrixToBottomSheetState,
        private val session: Session,
        private val stringProvider: StringProvider,
        private val rawService: RawService) : VectorViewModel<MatrixToBottomSheetState, MatrixToAction, MatrixToViewEvents>(initialState) {

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: MatrixToBottomSheetState): MatrixToBottomSheetViewModel
    }

    init {
        setState {
            copy(matrixItem = Loading())
        }
        viewModelScope.launch(Dispatchers.IO) {
            resolveLink(initialState)
        }
    }

    private suspend fun resolveLink(initialState: MatrixToBottomSheetState) {
        when {
            initialState.deepLink != null -> {
                val linkedId = PermalinkParser.parse(initialState.deepLink)
                if (linkedId is PermalinkData.FallbackLink) {
                    setState {
                        copy(
                                matrixItem = Fail(IllegalArgumentException(stringProvider.getString(R.string.permalink_malformed))),
                                startChattingState = Uninitialized
                        )
                    }
                    return
                }

                when (linkedId) {
                    is PermalinkData.UserLink -> {
                        val user = resolveUser(linkedId.userId)
                        setState {
                            copy(
                                    matrixItem = Success(user.toMatrixItem()),
                                    startChattingState = Success(Unit)
                            )
                        }
                    }
                    is PermalinkData.RoomLink -> {
                        // not yet supported
                    }
                    is PermalinkData.GroupLink -> {
                        // not yet supported
                    }
                    is PermalinkData.FallbackLink -> {
                    }
                }
            }
            initialState.userId != null   -> {
                val user = resolveUser(initialState.userId)

                setState {
                    copy(
                            matrixItem = Success(user.toMatrixItem()),
                            startChattingState = Success(Unit)
                    )
                }
            }
            else                          -> {
                setState {
                    copy(
                            matrixItem = Fail(IllegalArgumentException(stringProvider.getString(R.string.unexpected_error))),
                            startChattingState = Uninitialized
                    )
                }
            }
        }
    }

    private suspend fun resolveUser(userId: String): User {
        return tryOrNull {
                    awaitCallback<User> {
                        session.resolveUser(userId, it)
                    }
                }
                // Create raw user in case the user is not searchable
                ?: User(userId, null, null)
    }

    companion object : MvRxViewModelFactory<MatrixToBottomSheetViewModel, MatrixToBottomSheetState> {
        override fun create(viewModelContext: ViewModelContext, state: MatrixToBottomSheetState): MatrixToBottomSheetViewModel? {
            val fragment: MatrixToBottomSheet = (viewModelContext as FragmentViewModelContext).fragment()

            return fragment.matrixToBottomSheetViewModelFactory.create(state)
        }
    }

    override fun handle(action: MatrixToAction) {
        when (action) {
            is MatrixToAction.StartChattingWithUser -> handleStartChatting(action)
        }.exhaustive
    }

    private fun handleStartChatting(action: MatrixToAction.StartChattingWithUser) {
        val mxId = action.matrixItem.id
        val existing = session.getExistingDirectRoomWithUser(mxId)
        if (existing != null) {
            // navigate to this room
            _viewEvents.post(MatrixToViewEvents.NavigateToRoom(existing))
        } else {
            setState {
                copy(startChattingState = Loading())
            }
            // we should create the room then navigate
            viewModelScope.launch(Dispatchers.IO) {
                val adminE2EByDefault = rawService.getElementWellknown(session.myUserId)
                        ?.isE2EByDefault()
                        ?: true

                val roomParams = CreateRoomParams()
                        .apply {
                            invitedUserIds.add(mxId)
                            setDirectMessage()
                            enableEncryptionIfInvitedUsersSupportIt = adminE2EByDefault
                        }

                val roomId =
                        try {
                            awaitCallback<String> { session.createRoom(roomParams, it) }.also {
                                setState {
                                    copy(startChattingState = Success(Unit))
                                }
                            }
                        } catch (failure: Throwable) {
                            setState {
                                copy(startChattingState = Fail(Exception(stringProvider.getString(R.string.invite_users_to_room_failure))))
                            }
                            return@launch
                        }
                _viewEvents.post(MatrixToViewEvents.NavigateToRoom(roomId))
            }
        }
    }
}
