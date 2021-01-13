/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.samples.browser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_browser.view.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.toolbar.display.DisplayToolbar
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.privatemode.feature.SecureWindowFeature
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.session.CoordinateScrollingFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.sitepermissions.SitePermissionsRules
import mozilla.components.feature.sitepermissions.SitePermissionsRules.AutoplayAction
import mozilla.components.feature.toolbar.ToolbarFeature
import mozilla.components.lib.state.ext.consumeFlow
import mozilla.components.support.base.feature.PermissionsFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.arch.lifecycle.addObservers
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifAnyChanged
import org.mozilla.samples.browser.downloads.DownloadService
import org.mozilla.samples.browser.ext.components
import org.mozilla.samples.browser.integration.ContextMenuIntegration
import org.mozilla.samples.browser.integration.FindInPageIntegration
import org.mozilla.samples.browser.integration.P2PIntegration

/**
 * Base fragment extended by [BrowserFragment] and [ExternalAppBrowserFragment].
 * This class only contains shared code focused on the main browsing content.
 * UI code specific to the app or to custom tabs can be found in the subclasses.
 */
@SuppressWarnings("LargeClass")
abstract class BaseBrowserFragment : Fragment(), UserInteractionHandler {
    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val toolbarFeature = ViewBoundFeatureWrapper<ToolbarFeature>()
    private val contextMenuIntegration = ViewBoundFeatureWrapper<ContextMenuIntegration>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private val promptFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val findInPageIntegration = ViewBoundFeatureWrapper<FindInPageIntegration>()
    private val sitePermissionsFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val p2pIntegration = ViewBoundFeatureWrapper<P2PIntegration>()

    protected val sessionId: String?
        get() = arguments?.getString(SESSION_ID_KEY)

    @CallSuper
    @Suppress("LongMethod")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = inflater.inflate(R.layout.fragment_browser, container, false)

        layout.toolbar.display.menuBuilder = components.menuBuilder

        sessionFeature.set(
            feature = SessionFeature(
                components.store,
                components.sessionUseCases.goBack,
                layout.engineView,
                sessionId),
            owner = this,
            view = layout)

        toolbarFeature.set(
            feature = ToolbarFeature(
                layout.toolbar,
                components.store,
                components.sessionUseCases.loadUrl,
                components.defaultSearchUseCase,
                sessionId),
            owner = this,
            view = layout)

        layout.toolbar.display.setOnPermissionIndicatorClickedListener {
            sitePermissionsFeature.withFeature { feature ->
                feature.sitePermissionsRules = feature.sitePermissionsRules?.copy(
                    autoplayAudible = AutoplayAction.ALLOWED,
                    autoplayInaudible = AutoplayAction.ALLOWED
                )
                components.sessionUseCases.reload()
            }
        }

        layout.toolbar.display.indicators += listOf(
            DisplayToolbar.Indicators.TRACKING_PROTECTION
        )

        swipeRefreshFeature.set(
            feature = SwipeRefreshFeature(
                components.store,
                components.sessionUseCases.reload,
                layout.swipeToRefresh),
            owner = this,
            view = layout)

        downloadsFeature.set(
            feature = DownloadsFeature(
                requireContext().applicationContext,
                store = components.store,
                useCases = components.downloadsUseCases,
                fragmentManager = childFragmentManager,
                onDownloadStopped = { download, id, status ->
                    Logger.debug("Download done. ID#$id $download with status $status")
                },
                downloadManager = FetchDownloadManager(
                    requireContext().applicationContext,
                    components.store,
                    DownloadService::class
                ),
                tabId = sessionId,
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_DOWNLOAD_PERMISSIONS)
                }),
            owner = this,
            view = layout
        )

        val scrollFeature = CoordinateScrollingFeature(components.sessionManager, layout.engineView, layout.toolbar)

        contextMenuIntegration.set(
            feature = ContextMenuIntegration(
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                browserStore = components.store,
                tabsUseCases = components.tabsUseCases,
                contextMenuUseCases = components.contextMenuUseCases,
                parentView = layout,
                sessionId = sessionId
            ),
            owner = this,
            view = layout)

        appLinksFeature.set(
            feature = AppLinksFeature(
                context = requireContext(),
                store = components.store,
                sessionId = sessionId,
                fragmentManager = parentFragmentManager,
                launchInApp = { components.preferences.getBoolean(DefaultComponents.PREF_LAUNCH_EXTERNAL_APP, false) },
                loadUrlUseCase = components.sessionUseCases.loadUrl
            ),
            owner = this,
            view = layout
        )

        promptFeature.set(
            feature = PromptFeature(
                fragment = this,
                store = components.store,
                customTabId = sessionId,
                fragmentManager = parentFragmentManager,
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_PROMPT_PERMISSIONS)
                }),
            owner = this,
            view = layout)

        sitePermissionsFeature.set(
            feature = SitePermissionsFeature(
                context = requireContext(),
                sessionId = sessionId,
                storage = components.permissionStorage,
                fragmentManager = parentFragmentManager,
                sitePermissionsRules = SitePermissionsRules(
                    autoplayAudible = AutoplayAction.BLOCKED,
                    autoplayInaudible = AutoplayAction.BLOCKED,
                    camera = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    location = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    notification = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    microphone = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    persistentStorage = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    mediaKeySystemAccess = SitePermissionsRules.Action.ASK_TO_ALLOW
                ),
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_APP_PERMISSIONS)
                },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                store = components.store
            ),
            owner = this,
            view = layout
        )

        findInPageIntegration.set(
            feature = FindInPageIntegration(components.store, layout.findInPage, layout.engineView),
            owner = this,
            view = layout)

        p2pIntegration.set(
            feature = P2PIntegration(
                store = components.store,
                engine = components.engine,
                view = layout.p2p,
                thunk = { components.nearbyConnection },
                tabsUseCases = components.tabsUseCases,
                sessionUseCases = components.sessionUseCases
            ) { permissions ->
                requestPermissions(permissions, REQUEST_CODE_P2P_PERMISSIONS)
            },
            owner = this,
            view = layout
        )

        val secureWindowFeature = SecureWindowFeature(
            window = requireActivity().window,
            store = components.store,
            customTabId = sessionId
        )

        // Observe the lifecycle for supported features
        lifecycle.addObservers(
            scrollFeature,
            secureWindowFeature
        )

        return layout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        consumeFlow(components.store) { flow ->
            flow.mapNotNull { state -> state.findCustomTabOrSelectedTab(sessionId) }
                .ifAnyChanged { tab ->
                    arrayOf(
                        tab.content.loading,
                        tab.content.canGoBack,
                        tab.content.canGoForward
                    )
                }
                .collect {
                    view.toolbar.invalidateActions()
                }
        }
    }

    @CallSuper
    override fun onBackPressed(): Boolean =
        listOf(findInPageIntegration, toolbarFeature, sessionFeature).any { it.onBackPressed() }

    @CallSuper
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        val feature: PermissionsFeature? = when (requestCode) {
            REQUEST_CODE_DOWNLOAD_PERMISSIONS -> downloadsFeature.get()
            REQUEST_CODE_PROMPT_PERMISSIONS -> promptFeature.get()
            REQUEST_CODE_APP_PERMISSIONS -> sitePermissionsFeature.get()
            REQUEST_CODE_P2P_PERMISSIONS -> p2pIntegration.get()?.feature
            else -> null
        }
        feature?.onPermissionsResult(permissions, grantResults)
    }

    @CallSuper
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        promptFeature.withFeature { it.onActivityResult(requestCode, resultCode, data) }
    }

    companion object {
        private const val SESSION_ID_KEY = "session_id"

        private const val REQUEST_CODE_DOWNLOAD_PERMISSIONS = 1
        private const val REQUEST_CODE_PROMPT_PERMISSIONS = 2
        private const val REQUEST_CODE_APP_PERMISSIONS = 3
        internal const val REQUEST_CODE_P2P_PERMISSIONS = 4

        @JvmStatic
        protected fun Bundle.putSessionId(sessionId: String?) {
            putString(SESSION_ID_KEY, sessionId)
        }
    }
}
