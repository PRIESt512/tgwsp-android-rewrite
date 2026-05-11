package soft.shadlv.twp_rewritekts.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import soft.shadlv.twp_rewritekts.domain.LocalProxyViewModel.ProxyUiState
import soft.shadlv.twp_rewritekts.repository.ProxyConfigRepository

class ExternalProxyViewModel(application: Application) : AndroidViewModel(application) {
    private val context = getApplication<Application>()
    private val repository = ProxyConfigRepository(application)
    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState = _uiState.asStateFlow()


}