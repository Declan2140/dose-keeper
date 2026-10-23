package app.dosekeeper.billing

import android.app.Activity
import android.content.Context
import app.dosekeeper.BuildConfig
import app.dosekeeper.data.Settings
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The $5 one-time unlock, a Google Play in-app product. Play restores it on a new phone with no
 * account of ours. The last known state is cached so the app keeps working offline.
 */
class UnlockManager(
    context: Context,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _unlocked = MutableStateFlow(settings.unlockedCache || (BuildConfig.DEBUG && settings.debugUnlock))
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)
    /** The localized price from Play, e.g. "$4.99". Null until Play answers. */
    val price: StateFlow<String?> = _price.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var details: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun connect() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { refresh() }
            }

            override fun onBillingServiceDisconnected() = Unit // reconnects on the next purchase attempt
        })
    }

    /** Debug builds only: flip the unlock without Play, for testing. */
    fun setDebugUnlock(on: Boolean) {
        if (!BuildConfig.DEBUG) return
        settings.debugUnlock = on
        _unlocked.value = settings.unlockedCache || on
    }

    fun buy(activity: Activity) {
        val product = details
        if (!client.isReady || product == null) {
            _message.value = "Google Play isn't available right now. Check your connection and try again."
            connect()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        client.launchBillingFlow(activity, params)
    }

    fun restore() {
        if (!client.isReady) {
            _message.value = "Couldn't reach Google Play. Check your connection and try again."
            connect()
            return
        }
        scope.launch {
            refresh()
            _message.value = if (_unlocked.value) "Unlock restored. Thank you!" else "No previous purchase found on this Google account."
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private suspend fun refresh() {
        val productResult = client.queryProductDetails(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build(),
                    ),
                ).build(),
        )
        details = productResult.productDetailsList?.firstOrNull()
        _price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice

        val purchases = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (purchases.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            handle(purchases.purchasesList)
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { handle(purchases.orEmpty()) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { refresh() }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> _message.value = "The purchase didn't go through (${result.debugMessage.ifBlank { "code ${result.responseCode}" }})."
        }
    }

    private suspend fun handle(purchases: List<Purchase>) {
        val owned = purchases.filter { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        for (p in owned.filter { !it.isAcknowledged }) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
        }
        if (purchases.any { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PENDING }) {
            _message.value = "Your purchase is pending. The unlock turns on once Google Play confirms it."
        }
        val isOwned = owned.isNotEmpty()
        settings.unlockedCache = isOwned
        _unlocked.value = isOwned || (BuildConfig.DEBUG && settings.debugUnlock)
    }

    companion object {
        /** Must match the in-app product created in Play Console. */
        const val PRODUCT_ID = "full_unlock"

        /** Free tier limits from the spec. */
        const val FREE_PROFILES = 1
        const val FREE_HISTORY_DAYS = 30L
    }
}
