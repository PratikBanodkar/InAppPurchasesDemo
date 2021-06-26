package com.appedia.inapppurchasesdemo

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [AppCompatActivity] instance that shows 2 SKUs
 *
 * @property billingClient
 * The instance of [BillingClient] class that is used as interface between app and Google Play
 *
 * @property skuList
 * The [List]<[String]> containing productIDs of the SKUs for this demo. These IDs should match with
 * what we set in the Google Play Console
 *
 * @property skuDetailsList
 * The [List]<[SkuDetails]> that will be saving the details of all the SKUs mentioned in the [skuList]
 *
 * @property TAG
 * [String] constant used for logging
 */
class MainActivity : AppCompatActivity() {

    private lateinit var billingClient: BillingClient
    private val skuList = listOf("test_product_two", "test_product_one")
    private val TAG = MainActivity::class.java.simpleName
    private lateinit var skuDetailsList: List<SkuDetails>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupBillingClient()
        setClickListeners()
    }

    /**
     * Set up the [BillingClient] instance which will act as the interface between this app and
     * Google Play Billing Library.
     *
     * Requires a [PurchasesUpdatedListener] to listen to purchases when app is active.
     *
     * NOTE: Never perform any activity using the [BillingClient] before it's connection is established.
     */
    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(this)
            .enablePendingPurchases()
            .setListener(purchaseUpdateListener)
            .build()
        startConnection()
    }

    /**
     * Use the [BillingClient] to establish a connection to the Google Play.
     *
     * This takes a [BillingClientStateListener] to listen to state of the established connection.
     */
    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            /**
             * Called when the billing service gets disconnected from Google Play.
             *
             * Try to restart the connection on the next request to Google Play by
             * calling the [startConnection] method.
             */
            override fun onBillingServiceDisconnected() {
                showToast(R.string.billing_service_disconnected_toast_message)
                startConnection()
            }

            /**
             * Called when the billing setup has been finished.
             *
             * Now the [BillingClient] is ready and we can query purchases here.
             *
             * @param billingResult
             * An instance of [BillingResult] that indicates result of the connection establishment
             */
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    CoroutineScope(Dispatchers.Main).launch {
                        queryAvailableProducts()
                        fetchPurchases()
                    }
                }
            }
        })
    }

    /**
     * Queries the Google Play to return the details of SKUs defined.
     *
     * To get the available SKUs, call the [querySkuDetails] method on the [BillingClient].
     * This method takes an instance of [SkuDetailsParams] to which we pass the list of SKUs.
     * Google Play then returns a [List]<[SkuDetails]> which has details about the SKUs we passed.
     *
     */
    private suspend fun queryAvailableProducts() {
        val params = SkuDetailsParams.newBuilder()
        params.setSkusList(skuList).setType(BillingClient.SkuType.INAPP)

        val skuDetailsResult = withContext(Dispatchers.IO) {
            billingClient.querySkuDetails(params.build())
        }
        if (skuDetailsResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK
            && skuDetailsResult.skuDetailsList?.isNotEmpty() == true
        ) {
            //This list should contain the products added above
            this.skuDetailsList = skuDetailsResult.skuDetailsList!!
        }
    }


    /**
     * Util method to show [Toast] objects
     *
     * @param resID
     * Resource ID of the string to be displayed in the toast
     */
    private fun showToast(@StringRes resID: Int) {
        Toast.makeText(this, getString(resID), Toast.LENGTH_SHORT).show()
    }

    /**
     * Method to fetch past purchases of the user done on this app.
     *
     * For this we call the [queryPurchasesAsync] method on the [BillingClient] object.
     * From the [PurchasesResult] object we get the [List]<[Purchase]>.
     * With this list now we update the UI based on which SKU is owned and which is not.
     */
    private suspend fun fetchPurchases() {
        val purchaseResult = billingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP)
        if (purchaseResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            for (purchase in purchaseResult.purchasesList)
                handlePurchase(purchase)
        }
    }

    /**
     * The instance of [PurchasesUpdatedListener] which is passed while setting up the [BillingClient].
     * Listener interface for purchase updates which happen when, for example,
     * the user buys something within the app or by initiating a purchase from Google Play Store.
     *
     * Purchases within app and outside the app are reported here.
     *
     * @see [setupBillingClient]
     */
    private val purchaseUpdateListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d(TAG, "Purchases made = $purchases")
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            // Handle an error caused by a user cancelling the purchase flow.
            showToast(R.string.purchase_cancelled_by_user)

        } else {
            // Handle any other error codes.
            showToast(R.string.unexpected_error)
        }
    }

    /**
     * Method to acknowledge the purchase. This is a way for Google Play to know that the user
     * has gained entitlement of the content.
     *
     * Uses the [acknowledgePurchase] method of the [BillingClient].
     *
     * @param purchase
     * The [Purchase] that needs to be acknowledged.
     * Make sure it has not been acknowledged earlier before calling this method.
     *
     */
    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { billingResult ->
            val responseCode = billingResult.responseCode
            val debugMessage = billingResult.debugMessage
            updateUI(purchase)
            Log.d(TAG, debugMessage)
        }
    }

    /**
     * Method to handle any purchase that is delivered to [PurchasesUpdatedListener] or fetched by
     * [fetchPurchases] method.
     *
     * This method checks if the state of the purchase is [Purchase.PurchaseState.PURCHASED].
     *
     * If yes -> Check if purchase is acknowledged.
     * If not acknowledged ->  Calls [acknowledgePurchase] method
     * If acknowledge -> Updates the UI by calling [updateUI]
     */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            // SKU has been purchased
            if (!purchase.isAcknowledged) {
                // But SKU has not been acknowledged and user not given entitlement
                acknowledgePurchase(purchase)
            } else {
                updateUI(purchase)
            }
        }
    }

    /**
     * Method to update the UI for a SKU that is already purchased and owned.
     *
     * Update the button text and state based on the SKU product ID
     *
     * @param purchase
     * The [Purchase] which was either fetched from history or a new one.
     */
    private fun updateUI(purchase: Purchase) {
        for (sku in purchase.skus) {
            if (sku.equals("test_product_two")) {
                buttonBuyProductTwo.text = "OWNED"
                buttonBuyProductTwo.isEnabled = false
            } else {
                buttonBuyProductOne.text = "OWNED"
                buttonBuyProductOne.isEnabled = false
            }
        }
    }

    /**
     * Set Click Listeners to both the buttons in UI.
     *
     * If buttons are in Enabled state, it will trigger a billing flow by calling [launchBillingFlow]
     * method on the [BillingClient] object
     */
    private fun setClickListeners() {
        buttonBuyProductOne.setOnClickListener {
            val skuDetails = skuDetailsList.find { it.sku == "test_product_one" }!!
            val billingFlowParams = BillingFlowParams.newBuilder()
                .setSkuDetails(skuDetails)
                .build()
            billingClient.launchBillingFlow(this, billingFlowParams)
        }
        buttonBuyProductTwo.setOnClickListener {
            val skuDetails = skuDetailsList.find { it.sku == "test_product_two" }!!
            val billingFlowParams = BillingFlowParams.newBuilder()
                .setSkuDetails(skuDetails)
                .build()
            billingClient.launchBillingFlow(this, billingFlowParams)
        }
    }

}