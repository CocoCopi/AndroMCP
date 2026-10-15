package com.cococopi.andromcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cococopi.andromcp.data.repository.OAuthClientRepository
import com.cococopi.andromcp.di.IoDispatcher
import com.cococopi.andromcp.mcp.oauth.OAuthClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the Connected-clients screen: observes the registry and revokes clients. */
@HiltViewModel
class OAuthClientsViewModel
    @Inject
    constructor(
        private val repository: OAuthClientRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val clients: StateFlow<List<OAuthClient>> =
            repository
                .observeClients()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), emptyList())

        fun revoke(clientId: String) {
            viewModelScope.launch(ioDispatcher) { repository.revoke(clientId) }
        }

        private companion object {
            const val FLOW_TIMEOUT_MS = 5_000L
        }
    }
