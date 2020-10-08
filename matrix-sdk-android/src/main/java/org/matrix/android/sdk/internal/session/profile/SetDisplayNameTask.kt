/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.profile

import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.task.Task
import org.greenrobot.eventbus.EventBus
import javax.inject.Inject

internal abstract class SetDisplayNameTask : Task<SetDisplayNameTask.Params, Unit> {
    data class Params(
            val userId: String,
            val newDisplayName: String
    )
}

internal class DefaultSetDisplayNameTask @Inject constructor(
        private val profileAPI: ProfileAPI,
        private val eventBus: EventBus) : SetDisplayNameTask() {

    override suspend fun execute(params: Params) {
        return executeRequest(eventBus) {
            val body = SetDisplayNameBody(
                    displayName = params.newDisplayName
            )
            apiCall = profileAPI.setDisplayName(params.userId, body)
        }
    }
}
