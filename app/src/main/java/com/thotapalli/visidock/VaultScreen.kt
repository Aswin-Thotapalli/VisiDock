@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.thotapalli.visidock

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first

private data class CollectionSnapshot(val phase:String,val cards:List<Card>)
private data class Destination(val title: String, val icon: ImageVector)
private data class ScreenSnapshot(val route: String, val card: Card?=null, val draft: Card?=null, val preview: String?=null, val destination:Int=0,
    val backPreview:String?=null,val scanSide:Int?=null,val phase:String="",val photoBack:Boolean=false)
private val LocalPhotoAccount=staticCompositionLocalOf { "signed-out" }
private val LocalSharedScope=staticCompositionLocalOf<SharedTransitionScope?> {null}
private val LocalScreenScope=staticCompositionLocalOf<AnimatedVisibilityScope?> {null}
private val LocalInitialPhotoBack=staticCompositionLocalOf {false}
private val LocalSavedPhotoAlias=staticCompositionLocalOf<Pair<String,String>?> {null}
private val destinations = listOf(Destination("Collection", Icons.Outlined.Style), Destination("Favorites", Icons.Outlined.StarOutline), Destination("Settings", Icons.Outlined.Tune))

@Composable fun VaultScreen(vm: VaultViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.session?.uid) {CardBitmapCache.clear()}
    val haptic=LocalHapticFeedback.current
    var celebration by remember { mutableStateOf(false) }
    var receiptCard by remember {mutableStateOf<Card?>(null)}
    var receiptPreview by remember {mutableStateOf<String?>(null)}
    var receiptPhotoBack by remember {mutableStateOf(false)}
    var observedSave by remember { mutableLongStateOf(state.savedEvent) }
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var caseFocusId by rememberSaveable {mutableStateOf<String?>(null)}
    var compactCollection by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(state.savedEvent) {
        if(state.savedEvent>observedSave) {
            observedSave=state.savedEvent
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if(state.draft==null) {
                caseFocusId=receiptCard?.id ?: state.selectedId
                compactCollection=false;query="";tab=0;vm.select(null)
            }
            celebration=true;kotlinx.coroutines.delay(1800);celebration=false
        }
    }
    val collectionListState=rememberLazyListState()
    val favoritesListState=rememberLazyListState()
    var lastScrollQuery by rememberSaveable {mutableStateOf(query)}
    LaunchedEffect(query) {
        if(query!=lastScrollQuery) {
            collectionListState.scrollToItem(0)
            favoritesListState.scrollToItem(0)
            lastScrollQuery=query
        }
    }
    val navigationFocus=LocalFocusManager.current
    val navigationKeyboard=LocalSoftwareKeyboardController.current
    fun navigate(index:Int) {navigationFocus.clearFocus();navigationKeyboard?.hide();tab=index;vm.select(null)}
    var add by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let {uri->vm.stageCrop(uri)} }
    var capture by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var imports by rememberSaveable {mutableStateOf(false)}
    val launchDestination by DockLaunchRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(launchDestination,state.session?.uid,state.busy,state.draft?.id) {
        if(state.session!=null && state.draft==null && state.busy==null) when(launchDestination) {
            "scan"->{vm.beginCapture();capture=false;DockLaunchRequests.consume("scan")}
            "my_card"->{tab=2;vm.select(null);DockLaunchRequests.consume("my_card")}
            "import"->{imports=true;DockLaunchRequests.consume("import")}
        }
    }
    var cameraStartedNewCapture by rememberSaveable {mutableStateOf(false)}
    var awaitingCrop by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(awaitingCrop,state.cropPath,state.busy,state.error) {
        if(awaitingCrop) {
            // cameraResult starts the operation synchronously. Read the current VM
            // value so a pending collectAsState frame cannot look like a failed stage.
            val current=vm.state.value
            if(current.cropPath!=null || current.busy==null) {
                awaitingCrop=false;capture=null
                cameraStartedNewCapture=false
                if(current.cropPath==null && current.error==null) vm.report("The photo could not be prepared. Take another photo or import one.")
            }
        }
    }
    LaunchedEffect(state.message) { state.message?.let {
        // Confirmed saves already have their own visual receipt; avoid duplicate overlays.
        if(it!="Card saved") snackbar.showSnackbar(it)
        vm.clearMessage()
    } }
    val selected = state.cards.firstOrNull { it.id == state.selectedId }
    // Recognition can replace the draft identity; the photograph owns this journey.
    var lastReadBack by remember(state.draftPreview) {mutableStateOf(false)}
    val readingBack=state.draftBackPreview!=null && (state.scanSide ?: 0)>=1
    SideEffect {if(state.scanSide!=null) lastReadBack=readingBack}
    BackHandler(state.draft != null || state.selectedId != null || tab != 0) {
        when { state.captureReview -> discard=true; state.draft != null -> discard=true; state.selectedId != null -> vm.select(null); else -> tab=0 }
    }
    if(imports && state.session!=null) {
        BackHandler {imports=false}
        ImportWorkspace(checkNotNull(state.session).uid,onImportPair={item->imports=false;vm.importPair(item)},onQrContact={card->imports=false;vm.importContact(card)},onClose={imports=false})
        return
    }
    if(capture!=null && state.cropPath==null) {
        val back=capture==true
        fun cancelHandoff() {vm.cancelOperation();awaitingCrop=false;cameraStartedNewCapture=false;capture=null}
        Box(Modifier.fillMaxSize()) {
            CaptureScreen(back, vm::cameraFile, { success ->
                if(success) awaitingCrop=true
                vm.cameraResult(success,back)
            }, {
                vm.cameraResult(false)
                // Closing the initial camera abandons only the empty session created
                // for it. Closing a back/retake camera must retain the existing draft.
                if(cameraStartedNewCapture) vm.cancelCapture()
                cameraStartedNewCapture=false;awaitingCrop=false;capture=null
            })
            if(awaitingCrop) {
                BackHandler(onBack=::cancelHandoff)
                Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=64.dp,start=20.dp,end=20.dp),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(start=16.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        Text("Preparing photo",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                        DockTextButton(onClick=::cancelHandoff) {Text("Cancel")}
                    }
                }
            }
        }
        return
    }
    state.cropPath?.let {path ->
        CropScreen(path,state.cropBack,state.busy!=null,state.error,vm::useCrop,vm::cancelCrop,{
            val back=state.cropBack;vm.cancelCrop();capture=back
        })
        return
    }
    CompositionLocalProvider(LocalPhotoAccount provides state.session?.uid.orEmpty(),LocalSavedPhotoAlias provides receiptCard?.id?.let {id->receiptPreview?.let {id to it}}) {
    SharedTransitionLayout {
    val sharedScope=this
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        val rail = maxWidth >= 600.dp
        val showNav = state.session != null && state.draft == null && (expanded || selected == null)
        val localBusy=(state.draft!=null && state.busy?.startsWith("Saving")==true) ||
            (state.session==null && state.busy?.startsWith("Connecting")==true) ||
            (tab==2 && state.draft==null && state.busy?.startsWith("Updating your profile")==true)
        Scaffold(
            modifier=Modifier.background(MaterialTheme.colorScheme.background).materialUnderlay(),
            containerColor=Color.Transparent,
            contentColor=MaterialTheme.colorScheme.onBackground,
            snackbarHost={ SnackbarHost(snackbar) },
            bottomBar={
                AnimatedVisibility(showNav && !rail,enter=expandVertically(DockMotion.settle(480f,1f),expandFrom=Alignment.Bottom)+fadeIn(DockMotion.spec(120)),exit=shrinkVertically(DockMotion.spec(180),shrinkTowards=Alignment.Bottom)+fadeOut(DockMotion.spec(100))) {NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                    destinations.forEachIndexed { index, item -> DockNavigationBarItem(selected=tab==index, onClick={navigate(index)}, icon={ Icon(item.icon, null) }, label={ Text(item.title) }) }
                }}
            }

        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                AnimatedVisibility(showNav && rail,enter=expandHorizontally(DockMotion.settle(480f,1f))+fadeIn(DockMotion.spec(120)),exit=shrinkHorizontally(DockMotion.spec(180))+fadeOut(DockMotion.spec(100))) {NavigationRail(Modifier.fillMaxHeight(), containerColor=MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(20.dp)); Icon(Icons.Outlined.Style, "VisiDock", tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(32.dp))
                    destinations.forEachIndexed { index, item -> DockNavigationRailItem(selected=tab==index, onClick={navigate(index)}, icon={ Icon(item.icon, null) }, label={ Text(item.title) }) }
                }}
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedVisibility(state.busy != null && state.scanSide==null && !localBusy, enter=expandVertically(DockMotion.spec(180))+fadeIn(DockMotion.spec(100)), exit=shrinkVertically(DockMotion.spec(140))+fadeOut(DockMotion.spec(80))) {
                        Column(Modifier.fillMaxWidth().semantics { liveRegion=LiveRegionMode.Polite }) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.busy.orEmpty(), Modifier.padding(horizontal=24.dp, vertical=8.dp), style=MaterialTheme.typography.bodySmall)
                            if(state.busy?.startsWith("Downloading visual reading")==true) DockTextButton(onClick=vm::cancelOperation,modifier=Modifier.padding(horizontal=12.dp)) {Text("Cancel download")}
                        }
                    }
                    AnimatedContent(state.error,contentKey={it!=null},label="Operation outcome",transitionSpec={
                        (fadeIn(DockMotion.spec(130))+expandVertically(DockMotion.spec(200))) togetherWith (fadeOut(DockMotion.spec(80))+shrinkVertically(DockMotion.spec(160)))
                    }) {error-> if(error!=null) {
                        Surface(color=MaterialTheme.colorScheme.errorContainer) {
                            Row(Modifier.fillMaxWidth().padding(start=20.dp), verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ErrorOutline,null,tint=MaterialTheme.colorScheme.onErrorContainer)
                                Text(error, Modifier.weight(1f).padding(12.dp).semantics { liveRegion=LiveRegionMode.Assertive }, color=MaterialTheme.colorScheme.onErrorContainer, style=MaterialTheme.typography.bodyMedium)
                                DockIconButton(onClick=vm::clearError) {Icon(Icons.Outlined.Close,"Dismiss error")}
                            }
                        }
                    }}
                    val route = when {
                        !state.configured -> "setup"
                        state.session == null -> "auth"
                        state.scanSide!=null && state.draftPreview!=null -> "reading"
                        state.captureReview -> "captureReview"
                        state.draft != null -> "editor"
                        tab == 2 -> "settings"
                        selected != null && !expanded -> "detail"
                        else -> "collection"
                    }
                    val photoBack=when {
                        state.scanSide!=null -> readingBack
                        route=="captureReview" || route=="editor" -> lastReadBack
                        route=="detail" && selected?.id==receiptCard?.id -> receiptPhotoBack
                        else -> false
                    }
                    AnimatedContent(ScreenSnapshot(route,selected,state.draft,state.draftPreview,tab,state.draftBackPreview,state.scanSide,state.busy.orEmpty(),photoBack), contentKey={Triple(it.route,if(it.route=="collection") it.destination else 0,if(it.route=="detail") it.card?.id else null)}, modifier=Modifier.weight(1f), label="Screen transition", transitionSpec={
                        val photoJourney=(initialState.route in listOf("captureReview","reading","editor") && targetState.route in listOf("captureReview","reading","editor")) ||
                            (initialState.route in listOf("collection","detail","editor") && targetState.route in listOf("collection","detail","editor") && initialState.route!=targetState.route)
                        val direction=if(targetState.route=="collection" && initialState.route=="collection") {
                            if(targetState.destination>=initialState.destination) 1 else -1
                        } else if(targetState.route=="collection" || (initialState.route=="editor" && targetState.route=="detail")) -1 else 1
                        if(photoJourney) fadeIn(DockMotion.spec(180)) togetherWith fadeOut(DockMotion.spec(110))
                        else (fadeIn(DockMotion.spec(170)) + slideInHorizontally(DockMotion.settle(420f,.94f)) {direction*it/8} + scaleIn(DockMotion.spec(260),initialScale=if(direction>0 && android.animation.ValueAnimator.areAnimatorsEnabled()) .985f else 1f)) togetherWith
                            (fadeOut(DockMotion.spec(100)) + slideOutHorizontally(DockMotion.spec(180)) {-direction*it/12})
                    }) { screen -> CompositionLocalProvider(LocalSharedScope provides if(expanded && screen.route=="collection") null else sharedScope, LocalScreenScope provides this,LocalInitialPhotoBack provides screen.photoBack) { when(screen.route) {
                        "setup" -> SetupScreen()
                        "auth" -> AuthScreen(state.busy, vm::signIn, vm::resetPassword)
                        "captureReview" -> CaptureReviewScreen(state.copy(draft=screen.draft,draftPreview=screen.preview,draftBackPreview=screen.backPreview),vm,{capture=true},{discard=true})
                        "reading" -> if(screen.draft!=null && screen.preview!=null) ReadingStage(screen.draft,vm,screen.preview,screen.backPreview,screen.scanSide ?: 0,screen.phase,route=="reading",regions=state.scanRegions,analysisReady=state.scanAnalysisReady,onPresented=vm::finishScanPresentation,onCancel=vm::cancelOperation)
                        "editor" -> if(screen.draft != null) EditorScreen(state.copy(draft=screen.draft,draftPreview=screen.preview,draftBackPreview=screen.backPreview), vm, onBack={discard=true},onSave={card->receiptCard=card;receiptPreview=screen.preview;receiptPhotoBack=screen.photoBack;vm.save()})
                        "settings" -> SettingsScreen(state, vm,
                            onOwnCamera={vm.newOwnCard();cameraStartedNewCapture=true;capture=false},
                            onOwnImport={vm.newOwnCard();gallery.launch("image/*")},onImports={imports=true})
                        "detail" -> if(screen.card != null) DetailScreen(screen.card, vm, state.busy != null, onBack={vm.select(null)},onCaptureSide={card,back->vm.prepareSideEdit(card,back);capture=back})
                        else -> Row(Modifier.fillMaxSize()) {
                            CollectionScreen(state, query, {query=it}, screen.destination==1, vm, Modifier.weight(1f), if(screen.destination==1) favoritesListState else collectionListState, onAdd={add=true},caseFocusId=caseFocusId,onCaseFocus={caseFocusId=it},compact=compactCollection,onCompact={compactCollection=it})
                            if(expanded) {
                                VerticalDivider()
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    if(selected != null) DetailScreen(selected, vm, state.busy != null, onBack={vm.select(null)},onCaptureSide={card,back->vm.prepareSideEdit(card,back);capture=back})
                                    else EmptyState(Icons.Outlined.Badge, "Card details", "Select a card to view its details and photographs.", Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    } } }
                }
            }
        }
    }
    }
    }
    AnimatedVisibility(celebration,enter=fadeIn(DockMotion.spec(100))+slideInVertically(DockMotion.settle(430f,.88f)) {-it},exit=fadeOut(DockMotion.spec(100))+slideOutVertically(DockMotion.spec(160)) {-it/2}) {
        CompositionLocalProvider(LocalPhotoAccount provides state.session?.uid.orEmpty()) {
            SavedCardReceipt(state.cards.firstOrNull {it.id==receiptCard?.id} ?: receiptCard,vm,state.savedName,state.savedEvent)
        }
    }
    if(add) ModalBottomSheet(onDismissRequest={add=false}) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=32.dp).dockModalArrival(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Add a card", style=MaterialTheme.typography.headlineMedium)
            Text("Scan a card, import a photo or enter details.", color=MaterialTheme.colorScheme.onSurfaceVariant, modifier=Modifier.padding(bottom=16.dp))
            ActionRow(Icons.Outlined.PhotoCamera, "Take a photo", "Use your camera to capture a visiting card") {
                add=false
                vm.beginCapture();cameraStartedNewCapture=true;capture=false
            }
            ActionRow(Icons.Outlined.Image, "Import an image", "Choose a JPEG, PNG or WebP, up to 20 MB") { add=false; vm.beginCapture();gallery.launch("image/*") }
            ActionRow(Icons.Outlined.Edit, "Enter details", "Add details without a photograph") { add=false; vm.newCard() }
        }
    }
    if(discard) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={discard=false}, title={Text("Discard your changes?")}, text={Text("The unsaved details and photo will be removed from this device.",Modifier)},
        confirmButton={DockTextButton(enabled=state.busy==null, onClick={vm.discard(); discard=false}) {Text("Discard changes")}}, dismissButton={DockTextButton(onClick={discard=false}) {Text("Keep editing")}})
}

@Composable private fun CollectionScreen(state: VaultState, query: String, onQuery: (String)->Unit, favorites: Boolean, vm: VaultViewModel, modifier: Modifier, listState:LazyListState, onAdd: ()->Unit,caseFocusId:String?,onCaseFocus:(String)->Unit,compact:Boolean,onCompact:(Boolean)->Unit) {
    var results by remember { mutableStateOf(if(query.isBlank()) state.cards.filter {!favorites || it.favorite} else emptyList()) }
    var searching by remember { mutableStateOf(query.isNotBlank()) }
    var semantic by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var collectionFilter by rememberSaveable(favorites) {mutableStateOf<String?>(null)}
    val collections=remember(state.cards) {state.cards.flatMap {it.collections}.distinct().sorted()}
    LaunchedEffect(state.cards, query, favorites,collectionFilter) {
        searching=query.isNotBlank()
        val cards=state.cards.filter { (!favorites || it.favorite) && (collectionFilter==null || collectionFilter in it.collections) }
        results=withContext(Dispatchers.Default) { CardLogic.search(cards,query) }
        semantic=false; unavailable=false
        if(query.isNotBlank()) {
            searching=true
            try {
                kotlinx.coroutines.delay(180)
                val found=vm.search(cards,query)
                results=found.cards; semantic=found.semantic; unavailable=found.unavailable
            } finally { if(currentCoroutineContext().isActive) searching=false }
        }
    }
    BoxWithConstraints(modifier.fillMaxHeight()) {
    val compactAdd=maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
    val compactHeader=!compact || maxHeight < 500.dp || LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxSize()) {
        Surface(color=Color(0xFF102C60),contentColor=Color(0xFFF4F8FF),shape=androidx.compose.foundation.shape.RoundedCornerShape(bottomStart=24.dp,bottomEnd=24.dp)) {
            Column(Modifier.fillMaxWidth().materialUnderlay(.6f).padding(bottom=if(compactHeader) 16.dp else 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=16.dp), verticalAlignment=Alignment.CenterVertically) {
            Image(painterResource(R.drawable.visidock_mark),null,Modifier.size(32.dp).clip(MaterialTheme.shapes.small).background(Color(0xFF071D49)))
            Text("VisiDock", Modifier.padding(start=10.dp).weight(1f), style=MaterialTheme.typography.titleMedium)
            if(BuildConfig.DEMO) Surface(color=Color(0xFF244994),contentColor=Color(0xFFF4F8FF),shape=MaterialTheme.shapes.small) {Text("Demo",Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)}
        }
        Text(if(favorites) "Favorites" else state.session?.displayName?.takeIf {it.isNotBlank()}?.let {"${it.substringBefore(' ')}’s cards"} ?: "Cards", Modifier.padding(horizontal=24.dp), style=if(compactHeader) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(!compactHeader) Text(if(favorites) "${state.cards.count {it.favorite}} saved favorites" else "${state.cards.size} saved cards", Modifier.padding(horizontal=24.dp, vertical=8.dp), color=Color(0xFFB7C9E7))
            }
        }
        DockOutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=12.dp), singleLine=true,
            shape=MaterialTheme.shapes.large, leadingIcon={Icon(Icons.Outlined.Search, null)}, placeholder={Text("Search names, companies or notes",maxLines=1,overflow=TextOverflow.Ellipsis)},
            trailingIcon={if(query.isNotEmpty()) DockIconButton(onClick={onQuery("")}) {Icon(Icons.Outlined.Close, "Clear search")}},
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search))
        AnimatedVisibility(query.isNotBlank(),enter=expandVertically(DockMotion.spec(180))+fadeIn(DockMotion.spec(100)),exit=shrinkVertically(DockMotion.spec(140))+fadeOut(DockMotion.spec(80))) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=8.dp)) {
                AnimatedVisibility(searching,enter=expandVertically(DockMotion.spec(120)),exit=shrinkVertically(DockMotion.spec(120))) {LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))}
                val status=when {searching->"Searching on your device…";unavailable->"Smart search unavailable · showing keyword matches";semantic->"On-device smart search · related matches may vary";else->"Matching card details and notes"}
                AnimatedContent(status,label="Search feedback",transitionSpec={fadeIn(DockMotion.spec(120)) togetherWith fadeOut(DockMotion.spec(70))}) {message->Text(message,Modifier.padding(top=6.dp).semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        if(compact || query.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=12.dp), horizontalArrangement=Arrangement.SpaceBetween) {
            Text(if(query.isNotBlank()) "Search results" else if(favorites) "Favorites" else "Recently saved", style=MaterialTheme.typography.titleMedium)
            AnimatedContent(results.size,label="Result count",transitionSpec={
                (slideInVertically(DockMotion.spec(160)) {if(targetState>initialState) it else -it}+fadeIn(DockMotion.spec(120))) togetherWith (slideOutVertically(DockMotion.spec(100)) {if(targetState>initialState) -it else it}+fadeOut(DockMotion.spec(80)))
            }) {count->Text("$count ${if(count==1) "card" else "cards"}",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            DockFilterChip(selected=!compact,onClick={onCompact(false)},label={Text("Case")})
            DockFilterChip(selected=compact,onClick={onCompact(true)},label={Text("List")})
            Spacer(Modifier.weight(1f))
            if(compactAdd) DockIconButton(onClick=onAdd,enabled=state.busy==null) {
                Icon(Icons.Outlined.Add,"Add card")
            } else DockButton(onClick=onAdd,enabled=state.busy==null) {
                Icon(Icons.Outlined.Add,null,Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add card",maxLines=1)
            }
        }
        if(collections.isNotEmpty() || collectionFilter!=null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            DockFilterChip(collectionFilter==null,{collectionFilter=null},label={Text("All collections")})
            collections.forEach {name->DockFilterChip(collectionFilter==name,{collectionFilter=name},label={Text(name)})}
        }
        val snapshot=CollectionSnapshot(when {state.loading || (searching && results.isEmpty())->"loading";results.isEmpty()->"empty";else->"cards"},results)
        AnimatedContent(snapshot,contentKey={it.phase},modifier=Modifier.weight(1f),label="Collection results",transitionSpec={fadeIn(DockMotion.spec(160)) togetherWith fadeOut(DockMotion.spec(90))}) {content->
            when(content.phase) {
                "loading" -> CollectionLoading()
                "empty" -> if(!favorites && query.isBlank() && !compact) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {EmptyCardCase(onAdd,ownerName=state.session?.displayName.orEmpty())}
                } else EmptyState(if(query.isNotBlank()) Icons.Outlined.SearchOff else Icons.Outlined.Style,
                    if(query.isNotBlank()) "No matching cards" else if(favorites) "No favorites yet" else "No cards yet",
                    if(query.isNotBlank()) "Try fewer words, a company name or something you wrote in your notes." else if(favorites) "Tap the star on a card to keep it here." else "Scan a visiting card or enter its details.",
                    Modifier.fillMaxSize(),action=if(!favorites && query.isBlank()) onAdd else null,actionLabel="Add your first card")
                else -> if(!compact) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
                        CardCase(content.cards,caseFocusId,onCaseFocus,{onCaseFocus(it.id);vm.select(it)},vm::favorite,state.busy!=null,ownerName=state.session?.displayName.orEmpty()) {card->
                            CasePhoto(card,vm)
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp)) {
                    items(content.cards,key={it.id}) {card->
                        Column(Modifier.animateItem(fadeInSpec=DockMotion.spec(140),placementSpec=DockMotion.settle(480f,.96f),fadeOutSpec=DockMotion.spec(90))) {
                            CardRow(card,vm,state.selectedId==card.id,{vm.select(card)},{vm.favorite(card)},state.busy!=null)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }

    }
    }
}

@Composable private fun CardRow(card: Card, vm: VaultViewModel, selected: Boolean, onClick: ()->Unit, onFavorite: ()->Unit, busy: Boolean) {
    val haptic=LocalHapticFeedback.current
    val interactions=remember {MutableInteractionSource()}
    val pressed by interactions.collectIsPressedAsState()
    val scale=animateFloatAsState(if(pressed) .978f else 1f,if(pressed) DockMotion.spec(90) else DockMotion.settle(460f,.86f),label="Card lift")
    Surface(onClick={haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onClick()}, interactionSource=interactions, modifier=Modifier.graphicsLayer {scaleX=scale.value;scaleY=scale.value}.physicalSurface(depthDp=5f,pressedFraction={(1f-scale.value)/.022f},shape=MaterialTheme.shapes.large,material=PhysicalMaterial.Paper), color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape=MaterialTheme.shapes.large) {
        Column {
            if(card.hasFrontImage) CollectionPhoto(card,vm)
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                if(!card.hasFrontImage) Avatar(card,48)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(card.displayLabel,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(listOf(card.role,card.company.takeIf {card.name.isNotBlank()}.orEmpty()).filter(String::isNotBlank).joinToString(" · ").ifBlank {if(card.name.isBlank()) "Company card" else "Contact details"},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(card.notes.isNotBlank()) Text(card.notes,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                FavoriteControl(card,busy,onFavorite)
            }
        }
    }
}

@Composable internal fun cardImageTransition(id:String,back:Boolean=false):Modifier {
    val shared=LocalSharedScope.current
    val screen=LocalScreenScope.current
    val savedAlias=LocalSavedPhotoAlias.current
    val identity=savedAlias?.takeIf {it.first==id}?.second ?: id
    return if(shared!=null && screen!=null) with(shared) {
        Modifier.sharedElement(rememberSharedContentState("card-photo-$identity-$back"),animatedVisibilityScope=screen,boundsTransform={start,end->
            if(!android.animation.ValueAnimator.areAnimatorsEnabled()) tween(0)
            // One spring owns bounds throughout the journey. The former pair of
            // independently eased segments stopped and accelerated at its midpoint.
            else spring(dampingRatio=1f,stiffness=240f)
        })
    } else Modifier
}

private object CardBitmapCache {
    private val cache=object:android.util.LruCache<String,android.graphics.Bitmap>(20*1024*1024) {
        override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.allocationByteCount
    }
    @Synchronized fun get(key:String)=cache.get(key)
    @Synchronized fun put(key:String,value:android.graphics.Bitmap) {cache.put(key,value)}
    @Synchronized fun clear() {cache.evictAll()}
}
internal data class CardPhotoState(val bitmap:android.graphics.Bitmap?=null,val loading:Boolean=true)
@Composable internal fun cardPhoto(card:Card,vm:VaultViewModel,back:Boolean=false,local:String?=null,retry:Int=0):CardPhotoState {
    val account=LocalPhotoAccount.current
    val source=local ?: "${if(back) card.backImagePath else card.imagePath}|${card.sourceScanId}"
    val key="$account|${if(local!=null) "local" else card.id}|$back|$source"
    val initial=remember(key) {CardBitmapCache.get(key)}
    val result by key(key) { produceState(CardPhotoState(initial,initial==null),key,retry) {
        value=CardPhotoState(initial,initial==null)
        if(initial!=null) {value=CardPhotoState(initial,false);return@produceState}
        try {
            val bitmap=withContext(Dispatchers.IO) {
                CardBitmapCache.get(key) ?: (if(local!=null) ImageCropper.decode(java.io.File(local),1200) else vm.photo(card,back)?.let {bytes->BitmapFactory.decodeByteArray(bytes,0,bytes.size)})?.also {CardBitmapCache.put(key,it)}
            }
            value=CardPhotoState(bitmap,false)
        } catch(e:kotlinx.coroutines.CancellationException) {throw e} catch(e:Exception) {value=CardPhotoState(null,false)}
    }
    }
    return result
}
@Composable private fun CollectionPhoto(card:Card,vm:VaultViewModel) {
    var retry by remember(card.id,card.imagePath) {mutableIntStateOf(0)}
    val photo=cardPhoto(card,vm,retry=retry)
    Box(cardImageTransition(card.id).fillMaxWidth().height(190.dp).background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center) {
        CardPhotoContent(photo,"Card photo for ${card.displayLabel}","Load photo") {retry++}
        if(card.hasBackImage) Surface(Modifier.align(Alignment.BottomEnd).padding(10.dp),color=MaterialTheme.colorScheme.surface,shape=CircleShape) {Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {Icon(Icons.Outlined.Flip,null,Modifier.size(14.dp));Text("2 sides",style=MaterialTheme.typography.labelSmall)}}
    }
}

@Composable private fun CasePhoto(card:Card,vm:VaultViewModel) {
    var retry by remember(card.id,card.imagePath) {mutableIntStateOf(0)}
    val photo=cardPhoto(card,vm,retry=retry)
    Box(cardImageTransition(card.id).fillMaxSize(),contentAlignment=Alignment.Center) {
        if(card.hasFrontImage) CardPhotoContent(photo,"Card photo for ${card.displayLabel}","Load photo") {retry++}
        else Column(Modifier.fillMaxSize().materialUnderlay(.55f).padding(24.dp),verticalArrangement=Arrangement.Center) {
            Text(card.company.ifBlank {"VISIDOCK"},color=Color(0xFF376DA6),style=MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(16.dp));Text(card.displayLabel,color=Color(0xFF102C50),style=MaterialTheme.typography.headlineSmall)
            Text(card.role,color=Color(0xFF45617D),style=MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun CardPhotoContent(photo:CardPhotoState,description:String,retryLabel:String,onRetry:()->Unit) {
    AnimatedContent(photo,contentKey={when {it.bitmap!=null->"ready";it.loading->"loading";else->"error"}},modifier=Modifier.fillMaxSize(),label="Card image delivery",transitionSpec={fadeIn(DockMotion.spec(150)) togetherWith fadeOut(DockMotion.spec(80))}) {content->
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            when {
                content.bitmap!=null->Image(content.bitmap.asImageBitmap(),description,Modifier.fillMaxSize().padding(12.dp),contentScale=ContentScale.Fit)
                content.loading->LinearProgressIndicator(Modifier.width(64.dp))
                else->DockTextButton(onClick=onRetry) {Icon(Icons.Outlined.Refresh,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(retryLabel)}
            }
        }
    }
}

@Composable private fun CollectionLoading() {
    val band=if(android.animation.ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label="Loading collection").animateFloat(-.4f,1.4f,infiniteRepeatable(tween(1500,easing=LinearEasing)),label="Loading card surface")
    } else remember {mutableFloatStateOf(.5f)}
    val surface=MaterialTheme.colorScheme.surfaceContainer
    val line=MaterialTheme.colorScheme.outlineVariant
    val highlight=MaterialTheme.colorScheme.surface
    LazyColumn(Modifier.fillMaxSize().semantics {contentDescription="Loading saved cards"},contentPadding=PaddingValues(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        items(2) {
            Canvas(Modifier.fillMaxWidth().height(244.dp).clip(MaterialTheme.shapes.large)) {
                drawRect(surface)
                drawRoundRect(line,topLeft=Offset(18.dp.toPx(),185.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width*.55f,13.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                drawRoundRect(line,topLeft=Offset(18.dp.toPx(),210.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width*.38f,9.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                if(android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    val center=size.width*band.value
                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(highlight.copy(alpha=0f),highlight.copy(alpha=.42f),highlight.copy(alpha=0f)),startX=center-size.width*.3f,endX=center+size.width*.3f))
                }
            }
        }
    }
}

@Composable private fun Avatar(card: Card, size: Int) {
    val shared=LocalSharedScope.current
    val screen=LocalScreenScope.current
    val motion=if(shared!=null && screen!=null) with(shared) {
        Modifier.sharedElement(rememberSharedContentState("identity-${card.id}"),animatedVisibilityScope=screen,boundsTransform={_,_->tween(300,easing=FastOutSlowInEasing)})
    } else Modifier
    Surface(motion.size(size.dp), shape=CircleShape, color=MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment=Alignment.Center) { Text(card.displayLabel.split(' ').filter { it.isNotBlank() }.take(2).map { it.first().uppercase() }.joinToString(""), style=if(size>60) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium, color=MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}

@Composable private fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier=Modifier, action: (()->Unit)?=null, actionLabel: String="") {
    Column(modifier.fillMaxWidth().padding(32.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp), tint=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp)); Text(title, style=MaterialTheme.typography.titleLarge, textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(10.dp)); Text(body, Modifier.widthIn(max=360.dp), style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant, textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        if(action!=null) { Spacer(Modifier.height(24.dp)); DockButton(onClick=action) { Text(actionLabel) } }
    }
}

@Composable private fun ActionRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean=true, onClick: ()->Unit) {
    val haptic=LocalHapticFeedback.current
    val interactions=remember {MutableInteractionSource()}
    Surface(onClick={haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onClick()}, interactionSource=interactions, enabled=enabled, modifier=Modifier.dockPress(interactions), shape=MaterialTheme.shapes.medium, color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(vertical=16.dp, horizontal=8.dp), horizontalArrangement=Arrangement.spacedBy(16.dp), verticalAlignment=Alignment.CenterVertically) {
            Icon(icon, null, tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style=MaterialTheme.typography.titleMedium); Text(subtitle, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}

@Composable private fun Photo(card: Card, vm: VaultViewModel, local: String?=null, localBack: String?=null) {
    val hasFront=local!=null || card.hasFrontImage
    val hasBack=localBack!=null || card.hasBackImage
    if(!hasFront) return
    val haptic=LocalHapticFeedback.current
    val initialBack=LocalInitialPhotoBack.current && hasBack
    var back by rememberSaveable(card.id,local) { mutableStateOf(initialBack) }
    var retry by remember(card.id) { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    val turn=remember(card.id) {CardTurnState(back && hasBack,scope) {target->
        if(back!=target) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        back=target
    }}
    val enabled=android.animation.ValueAnimator.areAnimatorsEnabled()
    val visibleBack by remember(turn,enabled) {derivedStateOf {if(enabled) turn.showingBack else turn.targetBack}}
    var photoWidth by remember {mutableIntStateOf(1)}
    val held=animateFloatAsState(if(turn.dragging && enabled) 1f else 0f,DockMotion.settle(500f,.9f),label="Card engagement")
    val drag=if(hasBack) Modifier.pointerInput(turn,photoWidth) {
        val velocity=VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart={velocity.resetTracking();turn.beginDrag()},
            onDragEnd={turn.release(-velocity.calculateVelocity().x/photoWidth.coerceAtLeast(1)*180f)},
            onDragCancel={turn.release()},
            onHorizontalDrag={change,amount->
                change.consume();velocity.addPosition(change.uptimeMillis,change.position)
                turn.drag(-amount/photoWidth.coerceAtLeast(1)*180f)
            }
        )
    } else Modifier
    val frontPhoto=cardPhoto(card,vm,false,local,retry)
    val backPhoto=if(hasBack) cardPhoto(card,vm,true,localBack,retry) else CardPhotoState(loading=false)
    val shown=if(visibleBack) backPhoto else frontPhoto
    val navy=Color(0xFF10264B)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.large,
            modifier=cardImageTransition(local ?: card.id,visibleBack).fillMaxWidth().onSizeChanged {photoWidth=it.width}.then(drag).testTag("card-flip-${card.id}").semantics {
                stateDescription=if(visibleBack) "Showing back" else "Showing front"
                if(hasBack) customActions=listOf(CustomAccessibilityAction(if(turn.targetBack) "Turn to front" else "Turn to back") {turn.flip();true})
            }.drawBehind {
                // Keep the grounding shadow flat: native shadows on a rotated 3D
                // layer can project a long triangular artifact across nearby controls.
                val edge=if(enabled) kotlin.math.abs(kotlin.math.sin(turn.angle*Math.PI.toFloat()/180f)) else 0f
                drawOval(
                    brush=androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(navy.copy(alpha=.065f+edge*.14f),Color.Transparent),
                        center=Offset(size.width*.5f,size.height*.82f),radius=size.width*.48f),
                    topLeft=Offset(size.width*.05f,size.height*.60f),
                    size=androidx.compose.ui.geometry.Size(size.width*.9f,size.height*.4f))
            }.graphicsLayer {
                val pose=if(enabled) turn.angle else if(turn.targetBack) 180f else 0f
                val edge=kotlin.math.abs(kotlin.math.sin(pose*Math.PI.toFloat()/180f))
                rotationY=pose;cameraDistance=18*density
                rotationX=if(enabled) -3f*edge else 0f
                scaleX=1f-.025f*edge;scaleY=scaleX
                translationY=-held.value*3*density
                shadowElevation=0f
                ambientShadowColor=navy;spotShadowColor=navy
                shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp);clip=true
            }) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.65f).graphicsLayer {rotationY=if(visibleBack) 180f else 0f},contentAlignment=Alignment.Center) {
                CardPhotoContent(shown,"${if(visibleBack) "Back" else "Front"} of visiting card for ${card.displayLabel}","Image unavailable · Retry") {retry++}
                if(enabled) Canvas(Modifier.fillMaxSize()) {
                    val pose=turn.angle
                    val edge=kotlin.math.abs(kotlin.math.sin(pose*Math.PI.toFloat()/180f))
                    if(edge>.01f) {
                        val x=size.width*((pose%180f+180f)%180f)/180f
                        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White.copy(alpha=0f),Color.White.copy(alpha=edge*.14f),Color.White.copy(alpha=0f)),startX=x-size.width*.3f,endX=x+size.width*.3f))
                    }
                }
            }
        }
        if(hasBack) DockTextButton(onClick=turn::flip,haptic=false,modifier=Modifier.align(Alignment.CenterHorizontally).semantics {stateDescription=if(visibleBack) "Showing back" else "Showing front"}) {
            Icon(Icons.Outlined.Flip,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(turn.targetBack) "Turn to front" else "Turn to back")
        }
    }
}

@Composable private fun DetailScreen(card: Card, vm: VaultViewModel, busy: Boolean, onBack: ()->Unit, onCaptureSide:(Card,Boolean)->Unit) {
    val context=LocalContext.current
    var deleting by rememberSaveable(card.id) { mutableStateOf(false) }
    var contact by rememberSaveable(card.id) { mutableStateOf(false) }
    var imageActions by rememberSaveable(card.id) {mutableStateOf(false)}
    var importBack by rememberSaveable {mutableStateOf(false)}
    val sideGallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->uri?.let {vm.prepareSideEdit(card,importBack);vm.stageCrop(it,importBack)}}
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title={Text("Card details")}, navigationIcon={DockIconButton(onClick=onBack) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to collection")}}, actions={
            FavoriteControl(card,busy) {vm.favorite(card)}
            DockIconButton(enabled=!busy, onClick={vm.edit(card)}) {Icon(Icons.Outlined.Edit,"Edit card")}
        })
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Photo(card, vm)
            DockOutlinedButton(enabled=!busy,onClick={imageActions=true}) {Icon(Icons.Outlined.Crop,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Manage card photos")}
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(card.displayLabel, style=MaterialTheme.typography.headlineLarge); Text(listOf(card.role,card.company.takeIf {card.name.isNotBlank()}.orEmpty()).filter(String::isNotBlank).joinToString("\n"), style=MaterialTheme.typography.bodyLarge, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                DockButton(enabled=!busy, onClick={contact=true}) {Icon(Icons.Outlined.PersonAdd,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add to contacts")}
                DockOutlinedButton(enabled=!busy, onClick={vm.edit(card)}) {Text("Edit details")}
            }
            HorizontalDivider()
            card.contactPhones.forEachIndexed { index, phone ->
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(phone.label.ifBlank {"Phone ${index+1}"},style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.text.selection.SelectionContainer {Text(phone.number,style=MaterialTheme.typography.bodyLarge)}
                    }
                    DockIconButton(onClick={
                        if(phone.number.trim().matches(Regex("[+0-9() .-]+"))) runCatching {context.startActivity(Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",phone.number.filter {it.isDigit() || it=='+'},null)))}.onFailure {vm.report("No dialer is available on this device.")}
                        else vm.report("Check this phone number before opening it.")
                    }) {Icon(Icons.Outlined.Call,"Call ${phone.label.ifBlank {"phone ${index+1}"}} ${phone.number}")}
                }
            }
            (card.contactEmails.map {"Email" to it}+card.contactWebsites.map {"Website" to it}+listOf("Address" to card.address)).filter {it.second.isNotBlank()}.forEach { (label,value) ->
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant); androidx.compose.foundation.text.selection.SelectionContainer {Text(value,style=MaterialTheme.typography.bodyLarge)} }
                    DockIconButton(onClick={
                        val intent=when(label) {
                            "Phone" -> if(value.trim().matches(Regex("[+0-9() .-]+"))) Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",value.filter {it.isDigit() || it=='+'},null)) else null
                            "Email" -> Intent(Intent.ACTION_SENDTO,Uri.fromParts("mailto",value.trim(),null))
                            "Address" -> Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(value)))
                            else -> {
                                val uri=Uri.parse(if(value.contains("://")) value else "https://$value")
                                if(uri.scheme !in setOf("http","https") || uri.host.isNullOrBlank()) null else Intent(Intent.ACTION_VIEW,uri)
                            }
                        }
                        if(intent==null) vm.report("Check this detail before opening it.") else runCatching {context.startActivity(intent)}.onFailure {vm.report("No app is available to open this detail.")}
                    }) {Icon(when(label) {"Phone"->Icons.Outlined.Call;"Email"->Icons.Outlined.MailOutline;"Address"->Icons.Outlined.Place;else->Icons.Outlined.OpenInNew},"Open $label")}
                }
            }
            Surface(color=MaterialTheme.colorScheme.surfaceVariant, shape=MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {Text("Notes",style=MaterialTheme.typography.titleMedium); Text(card.notes.ifBlank {"No notes added."},color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if(card.rawText.isNotBlank() || card.backRawText.isNotBlank()) { var show by rememberSaveable(card.id) {mutableStateOf(false)}
                DockTextButton(onClick={show=!show}) {Text(if(show) "Hide recognized text" else "View recognized text"); DisclosureChevron(show)}
                AnimatedVisibility(show,enter=expandVertically(DockMotion.settle(440f,.95f))+fadeIn(DockMotion.spec(130)),exit=shrinkVertically(DockMotion.spec(180))+fadeOut(DockMotion.spec(80))) {Text(listOf(card.rawText,card.backRawText).filter(String::isNotBlank).joinToString("\n\nBack of card\n"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Text("Saved ${CardLogic.date(card.createdAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            CardHistoryAndNotes(card,vm)
            ContactSharing(card,vm)
            DockTextButton(enabled=!busy,onClick={deleting=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Icon(Icons.Outlined.DeleteOutline,null); Spacer(Modifier.width(8.dp)); Text("Delete card")}
        }
    }
    if(imageActions) ModalBottomSheet(onDismissRequest={imageActions=false}) {
        Column(Modifier.fillMaxWidth().padding(24.dp).dockModalArrival(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Card photos",style=MaterialTheme.typography.headlineMedium)
            if(card.hasFrontImage) ActionRow(Icons.Outlined.Crop,"Crop front","Adjust the saved front image",!busy) {imageActions=false;vm.recrop(card,false)}
            if(card.hasBackImage) ActionRow(Icons.Outlined.Crop,"Crop back","Adjust the saved back image",!busy) {imageActions=false;vm.recrop(card,true)}
            ActionRow(Icons.Outlined.PhotoCamera,if(!card.hasBackImage) "Add back photo" else "Replace back photo","Take a photo of the other side",!busy) {imageActions=false;onCaptureSide(card,true)}
            ActionRow(Icons.Outlined.Image,"Import back photo","Choose the other side from your photos",!busy) {imageActions=false;importBack=true;sideGallery.launch("image/*")}
            ActionRow(Icons.Outlined.PhotoCamera,"Replace front photo","Your saved contact details will stay intact",!busy) {imageActions=false;onCaptureSide(card,false)}
            Spacer(Modifier.height(16.dp))
        }
    }
    if(deleting) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={deleting=false},title={Text("Remove ${card.displayLabel}?")},text={Text(if(BuildConfig.DEMO) "This removes the sample card from this demo session." else "Move this card to the recovery bin. You can restore it from Settings.",Modifier)},confirmButton={DockTextButton(onClick={deleting=false;vm.delete(card)}) {Text("Remove card")}},dismissButton={DockTextButton(onClick={deleting=false}) {Text("Keep card")}})
    if(contact) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={contact=false},title={Text("Add to your phone contacts?")},text={Text("Your contacts app opens for review. Choose the destination account and check for an existing contact before saving. VisiDock does not read your phone contacts.",Modifier)},confirmButton={DockTextButton(onClick={
        contact=false
        val intent=Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
            putExtra(ContactsContract.Intents.Insert.NAME,card.name)
            putExtra(ContactsContract.Intents.Insert.COMPANY,card.company)
            putExtra(ContactsContract.Intents.Insert.JOB_TITLE,card.role); putExtra(ContactsContract.Intents.Insert.POSTAL,card.address)
            putExtra(ContactsContract.Intents.Insert.NOTES,card.notes)
            val data=arrayListOf<android.content.ContentValues>()
            card.contactPhones.forEach { phone -> data.add(android.content.ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER,phone.number)
                val type=when(phone.label.trim().lowercase()) {
                    "mobile","cell","cellular" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    "work","office" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                    "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
                    "fax","work fax" -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
                    "" -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                    else -> ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
                }
                put(ContactsContract.CommonDataKinds.Phone.TYPE,type)
                if(type==ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) put(ContactsContract.CommonDataKinds.Phone.LABEL,phone.label)
            }) }
            card.contactEmails.forEach {email->data.add(android.content.ContentValues().apply {put(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);put(ContactsContract.CommonDataKinds.Email.ADDRESS,email)})}
            card.contactWebsites.forEach {website->data.add(android.content.ContentValues().apply {put(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE);put(ContactsContract.CommonDataKinds.Website.URL,website)})}
            if(data.isNotEmpty()) putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA,data)
        }
        runCatching {context.startActivity(intent)}.onFailure {vm.report("No contacts app is available on this device.")}
    }) {Text("Open contacts")}},dismissButton={DockTextButton(onClick={contact=false}) {Text("Cancel")}})
}

@Composable private fun EditorScreen(state: VaultState, vm: VaultViewModel, onBack: ()->Unit, onSave:(Card)->Unit) {
    val card=checkNotNull(state.draft)
    val busy=state.busy!=null
    val existing=state.cards.any {it.id==card.id}
    val duplicate=CardLogic.duplicates(card,state.cards)
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var validationShown by rememberSaveable(card.id) {mutableStateOf(false)}
    var focusAttempt by remember(card.id) {mutableIntStateOf(0)}
    var requestedField by remember(card.id) {mutableStateOf<String?>(null)}
    val emailInvalid=card.email.isNotBlank() && ContactChannels.email(card.email)!=card.email.trim()
    val websiteInvalid=card.website.isNotBlank() && ContactChannels.website(card.website)!=card.website.trim()
    val invalidEmailIndex=editableChannels(card.email,card.emails).indexOfFirst {it.isNotBlank() && ContactChannels.email(it)!=it.trim()}
    val invalidWebsiteIndex=editableChannels(card.website,card.websites).indexOfFirst {it.isNotBlank() && ContactChannels.website(it)!=it.trim()}
    val firstInvalid=when {invalidEmailIndex>=0->"Email ${invalidEmailIndex+1}";invalidWebsiteIndex>=0->"Website ${invalidWebsiteIndex+1}";else->null}
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title={Text(if(existing) "Edit card" else "Review your card",maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={DockIconButton(enabled=!busy,onClick=onBack) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Cancel editing")}},actions={
            // Keep the commit target stationary while Android animates the keyboard.
            DockButton(loading=state.busy?.startsWith("Saving")==true,enabled=!busy && !state.ownCardChoiceRequired && card.displayLabel.isNotBlank(),onClick={
                validationShown=true
                if(firstInvalid!=null) {requestedField=firstInvalid;focusAttempt++}
                else {focus.clearFocus();keyboard?.hide();onSave(card)}
            },modifier=Modifier.padding(end=12.dp)) {
                Text(if(state.busy?.startsWith("Saving")==true) "Saving…" else if(existing) "Save changes" else if(state.remainingPeople>0) "Save & next" else "Save card",maxLines=1)
            }
        })
        Column(Modifier.weight(1f).widthIn(max=720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).dockReflow(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(!existing) Text("Check the details against the photo. Text recognition can make mistakes.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.ownCardChoiceRequired) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp).dockReflow(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Which person is you?",style=MaterialTheme.typography.titleMedium)
                    Text("This card contains several people. Choose your details for My card before saving. Each other person stays a separate contact.",style=MaterialTheme.typography.bodySmall)
                    vm.ownCardCandidates().forEachIndexed {index,candidate->
                        DockSelectionRow(label=candidate.displayLabel.ifBlank {"Person ${index+1}"},checked=false,
                            onCheckedChange={if(it) vm.chooseOwnCard(candidate.id)},enabled=!busy,
                            supportingText=listOf(candidate.role,candidate.company).filter(String::isNotBlank).joinToString(" \u00B7 "))
                    }
                }
            }
            Photo(card,vm,state.draftPreview,state.draftBackPreview)
            if(!busy && (state.draftPreview!=null || state.draftBackPreview!=null)) QrEvidenceReview(card,state.draftPreview,state.draftBackPreview,vm::changeDraft)
            if(!existing && state.draftPreview!=null && state.visualModelReady) DockTextButton(enabled=!busy,onClick=vm::readPhotosAgain) {Text("Read photos again")}
            if(state.remainingPeople>0) {
                Text("${state.remainingPeople + 1} people to review · each saves as a separate contact",style=MaterialTheme.typography.titleMedium)
                DockTextButton(enabled=!busy && !state.ownCardChoiceRequired,onClick={focus.clearFocus();keyboard?.hide();vm.skipPerson()}) {Text("Skip person")}
            }
            if(state.extractionWarnings.isNotEmpty()) AnimatedContent(state.extractionWarnings,contentKey={it.isEmpty()},label="Extraction guidance",transitionSpec={
                (expandVertically(DockMotion.spec(200))+fadeIn(DockMotion.spec(140))) togetherWith (shrinkVertically(DockMotion.spec(160))+fadeOut(DockMotion.spec(90)))
            }) {warnings->if(warnings.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Check against the card",style=MaterialTheme.typography.titleMedium)
                    warnings.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                }
            }}
            if(duplicate.isNotEmpty()) AnimatedContent(duplicate.map {it.name},contentKey={it.isEmpty()},label="Duplicate guidance",transitionSpec={
                (expandVertically(DockMotion.spec(200))+fadeIn(DockMotion.spec(140))) togetherWith (shrinkVertically(DockMotion.spec(160))+fadeOut(DockMotion.spec(90)))
            }) {names->if(names.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {Text("Possible duplicate: ${names.joinToString()}. This email or phone is already in your collection.",Modifier.padding(16.dp))}}
            fun change(value: Card) {
                val previousValidation=CardLogic.validate(card)
                vm.changeDraft(value)
                if(previousValidation!=null && state.error==previousValidation) vm.clearError()
            }
            @Composable fun field(label: String,value: String,limit: Int,type: KeyboardType=KeyboardType.Text,multi: Boolean=false,update:(String)->Unit) {
                val unfold=remember(card.id,label) {Animatable(if(!existing && state.draftPreview!=null) 0f else 1f)}
                LaunchedEffect(card.id) {unfold.animateTo(1f,DockMotion.settle(280f,.92f))}
                val issue=if(!validationShown) null else when {label=="Email" && emailInvalid->"Check the email address, including @ and its domain.";label=="Website" && websiteInvalid->"Enter a website here; email addresses belong above.";else->null}
                val request=remember(label) {FocusRequester()}
                val bring=remember(label) {BringIntoViewRequester()}
                // An explicit rejected save owns focus once. Correcting one field must
                // not redirect typing into the next invalid field or react to network work.
                LaunchedEffect(focusAttempt) {if(focusAttempt>0 && label==requestedField) {request.requestFocus();bring.bringIntoView()}}
                DockOutlinedTextField(value,{update(it.take(limit))},Modifier.fillMaxWidth().graphicsLayer {
                    transformOrigin=androidx.compose.ui.graphics.TransformOrigin(.5f,0f)
                    rotationX=(1f-unfold.value)*-14f;translationY=(1f-unfold.value)*-36.dp.toPx()
                    alpha=.35f+.65f*unfold.value;cameraDistance=18*density
                }.focusRequester(request).bringIntoViewRequester(bring),enabled=!busy,label={Text(label)},singleLine=!multi,minLines=if(multi) 3 else 1,isError=issue!=null,supportingText=if(issue==null) null else {
                    {
                    AnimatedVisibility(issue!=null,enter=expandVertically(DockMotion.spec(180))+fadeIn(DockMotion.spec(100)),exit=shrinkVertically(DockMotion.spec(140))+fadeOut(DockMotion.spec(70))) {Text(issue.orEmpty())}
                    }
                },keyboardOptions=KeyboardOptions(keyboardType=type,capitalization=if(type==KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None))
            }
            SectionTitle(Icons.Outlined.Badge,"Identity")
            if(card.name.isBlank() && card.company.isBlank()) Text("Add a person or company name to save this card.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            field("Full name",card.name,200) {change(card.copy(name=it))}
            FieldEvidence(state,"name")
            field("Job title",card.role,300) {change(card.copy(role=it))}
            FieldEvidence(state,"role")
            field("Company",card.company,300) {change(card.copy(company=it))}
            FieldEvidence(state,"company")
            Spacer(Modifier.height(4.dp))
            SectionTitle(Icons.Outlined.ContactPhone,"Contact details")
            val editablePhones=card.phones.ifEmpty {card.contactPhones.ifEmpty {listOf(PhoneNumber(""))}}
            PhoneEditor(card.id,editablePhones,busy,validationShown) {values->change(card.copy(phones=values,phone=values.firstOrNull {it.number.isNotBlank()}?.number.orEmpty()))}
            ChannelEditor("Email",editableChannels(card.email,card.emails),busy,validationShown,focusAttempt,requestedField) {values->change(card.copy(emails=values,email=values.firstOrNull {it.isNotBlank()}.orEmpty()))}
            FieldEvidence(state,"email")
            RepeatedFieldEvidence(state,"emails")
            ChannelEditor("Website",editableChannels(card.website,card.websites),busy,validationShown,focusAttempt,requestedField) {values->change(card.copy(websites=values,website=values.firstOrNull {it.isNotBlank()}.orEmpty()))}
            FieldEvidence(state,"website")
            RepeatedFieldEvidence(state,"websites")
            field("Address",card.address,1000,multi=true) {change(card.copy(address=it))}
            FieldEvidence(state,"address")
            CardOrganizationEditor(card,busy,::change)
            SectionTitle(Icons.Outlined.Notes,"Notes")
            field("Notes about this card",card.notes,4000,multi=true) {change(card.copy(notes=it))}
            if(card.rawText.isNotBlank()) {
                var source by rememberSaveable(card.id) {mutableStateOf(false)}
                DockTextButton(onClick={source=!source}) {Icon(Icons.Outlined.DocumentScanner,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(source) "Hide original reading" else "Compare with original reading")}
                AnimatedVisibility(source,enter=expandVertically(DockMotion.settle(440f,.95f))+fadeIn(DockMotion.spec(130)),exit=shrinkVertically(DockMotion.spec(180))+fadeOut(DockMotion.spec(80))) { Column {Text(card.rawText,style=MaterialTheme.typography.bodyMedium); if(card.backRawText.isNotBlank()) {Spacer(Modifier.height(12.dp)); Text("Back of card",style=MaterialTheme.typography.titleSmall);Text(card.backRawText,style=MaterialTheme.typography.bodyMedium)}} }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun SettingsScreen(state: VaultState,vm: VaultViewModel,onOwnCamera:()->Unit,onOwnImport:()->Unit,onImports:()->Unit) {
    val context=LocalContext.current
    var delete by rememberSaveable {mutableStateOf(false)}
    var learningHistory by rememberSaveable {mutableStateOf(false)}
    var password by remember {mutableStateOf("")}
    val busy=state.busy!=null
    Column(Modifier.widthIn(max=720.dp).fillMaxHeight().fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).dockReflow(),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("Settings",style=MaterialTheme.typography.headlineLarge)
        Surface(color=Color(0xFF102C60),contentColor=Color(0xFFF4F8FF),shape=MaterialTheme.shapes.large) {
            Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.AccountCircle,null,Modifier.size(40.dp),tint=Color(0xFF99DDEB))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    AnimatedContent(state.session?.displayName?.ifBlank {"Your account"} ?: "Your account",label="Saved profile name",transitionSpec={fadeIn(DockMotion.spec(160)) togetherWith fadeOut(DockMotion.spec(90))}) {name->Text(name,style=MaterialTheme.typography.titleLarge)}
                    Text(state.session?.email.orEmpty(),style=MaterialTheme.typography.bodyMedium,color=Color(0xFFB7C9E7))
                }
            }
        }
        var profileName by remember(state.session?.displayName) { mutableStateOf(state.session?.displayName.orEmpty()) }
        DockOutlinedTextField(profileName,{profileName=it.take(100)},Modifier.fillMaxWidth(),label={Text("Display name")},singleLine=true)
        DockTextButton(enabled=!busy && profileName.isNotBlank(),onClick={vm.updateDisplayName(profileName)}) {
            if(state.busy?.startsWith("Updating your profile")==true) {CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
            Text(if(state.busy?.startsWith("Updating your profile")==true) "Saving name…" else "Save profile name")
        }
        SettingsCompartment("My card & collection tools","Your own card, imports, organization, export and backup") {
            CollectionTools(state,vm,onOwnCamera,onOwnImport)
            DockOutlinedButton(onClick=onImports,enabled=!busy) {Text("Import images, PDF or QR contacts")}
        }
        SettingsCompartment("Security","App lock and screen privacy") {SecuritySettings()}
        SettingsCompartment("Sync & recovery","${state.localVault.pendingCount} queued · ${state.localVault.deletedCards.size} removed · ${state.localVault.conflicts.size} conflicts") {RecoveryAndSync(state,vm)}
        SettingsCompartment("Recognition controls","Card scripts and private diagnostics") {RecognitionSettings(vm::report)}
        if(BuildConfig.DEMO) Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {Text("Demo workspace\nThese are fictional contacts. Changes are held in memory and reset when the app process restarts. Use sample images only.",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onPrimaryContainer)}
        if(!BuildConfig.DEMO && state.session?.verified==false) {
            Text("Verify your email",style=MaterialTheme.typography.titleMedium)
            Text("Confirm your address to help protect access to your account.")
            DockOutlinedButton(enabled=!busy,onClick=vm::verifyEmail) {Text("Send verification email")}
            DockTextButton(enabled=!busy,onClick=vm::refreshSession) {Text("I’ve verified my email")}
            DockTextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://visidock-thotapalli.web.app/auth")))}.onFailure {vm.report("Open visidock-thotapalli.web.app/auth in your browser for verification help.")}}) {Text("Verification help")}
        }
        HorizontalDivider()
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learn from my corrections",style=MaterialTheme.typography.titleMedium)
                AnimatedContent(state.learningEnabled,label="Learning preference",transitionSpec={fadeIn(DockMotion.spec(140)) togetherWith fadeOut(DockMotion.spec(80))}) {enabled->
                    Text(if(enabled) "Saved corrections help recognition adapt privately in the background. Learning data stays on this device." else "Learning is off. Your existing history stays on this device until you clear it.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DockSwitch(checked=state.learningEnabled,onCheckedChange=vm::setLearning,enabled=!busy)
        }
        DockTextButton(enabled=!busy,onClick={learningHistory=true;vm.inspectLearning()}) {Text("Correction history · ${state.correctionHistory.size}")}
        SectionTitle(Icons.Outlined.DocumentScanner,"Visual reading")
        AnimatedContent(state.visualModelReady,label="Model installation",transitionSpec={
            (fadeIn(DockMotion.spec(160))+expandVertically(DockMotion.spec(200))) togetherWith (fadeOut(DockMotion.spec(80))+shrinkVertically(DockMotion.spec(160)))
        }) {ready->
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(if(ready) Icons.Outlined.CheckCircle else Icons.Outlined.Download,null,tint=MaterialTheme.colorScheme.primary)
                    Text(if(ready) "Visual reading is installed. It looks at card photographs on this device and can propose more than one person. Always review the results." else "Download the visual model once (2.6 GB). It reads the layout and people on the card without sending photographs to an AI service. Use Wi-Fi and allow at least 3.1 GB free space.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(!ready) DockOutlinedButton(enabled=!busy,onClick=vm::downloadVisualModel) {Text(if(state.busy?.startsWith("Downloading visual reading")==true) "Downloading…" else "Download visual reading")}
            }
        }
        HorizontalDivider()
        SectionTitle(Icons.Outlined.Shield,"Privacy & storage")
        Text("Text recognition runs on your device. In the connected app, card details are stored in your private Firebase collection and photographs in private Cloudflare R2 storage. Cloud storage is not end-to-end encrypted.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Smart search uses an English-language model bundled with VisiDock. Card details, notes and search queries are processed on this device. No model provider receives them. Related matches are suggestions, not facts.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Contacts are added only when you choose to open the phone’s contact editor and save there.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        ActionRow(Icons.Outlined.Sync,"Retry sync","Reload cards and retry pending image cleanup",!busy,vm::retry)
        if(!BuildConfig.DEMO) {
            ActionRow(Icons.Outlined.LockReset,"Reset password","Send a reset link to your email",!busy) {vm.resetPassword(state.session?.email.orEmpty())}
            DockOutlinedButton(enabled=!busy,onClick=vm::signOut) {Text("Sign out")}
            DockTextButton(enabled=!busy,onClick={delete=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("Delete account and all cards")}
        }
        Text("VisiDock ${BuildConfig.VERSION_NAME} · ${if(BuildConfig.DEMO) "Demo" else "Cloud"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(learningHistory) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={learningHistory=false},title={Text("Your corrections")},text={
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("${state.correctionHistory.size} recent saved changes · ${state.learnedLabels.size} remembered field labels",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(state.busy?.contains("learning history")==true,enter=expandVertically(DockMotion.spec(120)),exit=shrinkVertically(DockMotion.spec(120))) {LinearProgressIndicator(Modifier.fillMaxWidth())}
            AnimatedContent(state.correctionHistory,contentKey={it.isEmpty()},label="Correction history result",transitionSpec={fadeIn(DockMotion.spec(150)) togetherWith fadeOut(DockMotion.spec(90))}) {history->Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(history.isEmpty()) Text("New corrections will appear here after you save. Earlier versions did not record every change.")
            history.asReversed().forEach {change->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(change.field.replaceFirstChar {it.uppercase()},style=MaterialTheme.typography.titleSmall)
                    Text(change.before.ifBlank {"Empty"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(16.dp));Text(change.after.ifBlank {"Removed"},style=MaterialTheme.typography.bodyMedium)}
                    Text(if(change.eligibleForLearning) "Available for local learning" else "Saved correction · not used as a training example",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            }}
        }
    },confirmButton={DockTextButton(onClick={learningHistory=false}) {Text("Done")}},dismissButton={DockTextButton(enabled=!busy && (state.learnedLabels.isNotEmpty() || state.correctionHistory.isNotEmpty()),onClick=vm::clearLearning) {Text("Clear history")}})
    if(delete) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={delete=false;password=""},title={Text("Delete your account?")},text={Column(Modifier,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("All saved cards and images will be permanently removed before your account is deleted. Enter your password to confirm.")
        DockOutlinedTextField(password,{password=it},label={Text("Current password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
    }},confirmButton={DockTextButton(enabled=password.isNotBlank(),onClick={val value=password;password="";delete=false;vm.deleteAccount(value)}) {Text("Delete permanently")}},dismissButton={DockTextButton(onClick={delete=false;password=""}) {Text("Keep account")}})
}

@Composable internal fun AuthScreen(busyLabel: String?,onSubmit:(String,String,Boolean,String)->Unit,onReset:(String)->Unit) {
    val busy=busyLabel!=null
    var email by rememberSaveable {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var register by rememberSaveable {mutableStateOf(false)}
    var profileName by rememberSaveable {mutableStateOf("")}
    var visible by remember {mutableStateOf(false)}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxSize().imePadding(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Surface(color=Color(0xFF102C60),contentColor=Color.White,shape=MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.drawable.visidock_mark),null,Modifier.size(56.dp))
                    Text("VisiDock",style=MaterialTheme.typography.headlineMedium)
                }
            }
            AnimatedContent(register,label="Account mode",transitionSpec={
                (fadeIn(DockMotion.spec(140))+slideInHorizontally(DockMotion.spec(220)) {if(targetState) it/5 else -it/5}) togetherWith (fadeOut(DockMotion.spec(80))+slideOutHorizontally(DockMotion.spec(160)) {if(targetState) -it/6 else it/6})
            }) {creating->Text(if(creating) "Create your\ncollection." else "Welcome back.",style=MaterialTheme.typography.headlineLarge)}
            Text(if(register) "Create an account to save your cards." else "Sign in to your card collection.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(register,enter=expandVertically(DockMotion.spec())+fadeIn(),exit=shrinkVertically(DockMotion.spec())+fadeOut()) {DockOutlinedTextField(profileName,{profileName=it.take(100)},Modifier.fillMaxWidth(),label={Text("Your name")},singleLine=true,enabled=!busy)}
            DockOutlinedTextField(email,{email=it.trim()},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Email address")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
            DockOutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Password")},singleLine=true,visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={DockIconButton(onClick={visible=!visible}) {Icon(if(visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible) "Hide password" else "Show password")}})
            AnimatedVisibility(register,enter=expandVertically(DockMotion.spec(180)),exit=shrinkVertically(DockMotion.spec(140))) {Text("Use at least 8 characters.",style=MaterialTheme.typography.bodySmall)}
            DockButton(loading=busyLabel?.startsWith("Connecting")==true,enabled=!busy && email.isNotBlank() && password.length>=if(register) 8 else 6,onClick={focus.clearFocus();keyboard?.hide();onSubmit(email,password,register,profileName)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(register) "Create account" else "Sign in")}
            AnimatedVisibility(!register,enter=expandVertically(DockMotion.spec(180)),exit=shrinkVertically(DockMotion.spec(140))) {DockTextButton(enabled=!busy,onClick={onReset(email)}) {Text("Forgot password?")}}
            DockTextButton(enabled=!busy,onClick={focus.clearFocus();keyboard?.hide();register=!register;password=""}) {Text(if(register) "Already have an account? Sign in" else "New here? Create an account")}
        }
    }
}

@Composable private fun SetupScreen() {
    EmptyState(Icons.Outlined.CloudOff,"Connect your workspace","This cloud build needs its Firebase Android configuration. Add app/google-services.json and rebuild, or install the separate demo build to explore VisiDock.",Modifier.fillMaxSize())
}





/** Capture is a distinct step: both photographs exist before recognition starts. */
@Composable private fun CaptureReviewScreen(state:VaultState,vm:VaultViewModel,onBackCamera:()->Unit,onCancel:()->Unit) {
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->uri?.let {vm.stageCrop(it,true)}}
    val busy=state.busy!=null
    val hasBack=state.draftBackPreview!=null
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title={Text("Card photos")},navigationIcon={DockIconButton(enabled=!busy,onClick=onCancel) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Discard capture")}})
        Column(Modifier.weight(1f).widthIn(max=640.dp).fillMaxWidth().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(24.dp).dockReflow(),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text(if(hasBack) "Both sides. Ready." else "Anything on the back?",style=MaterialTheme.typography.headlineLarge)
            Text(if(hasBack) "We’ll read these together as one card." else "Add the other side now, or continue with the front.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.draft?.let {Photo(it,vm,state.draftPreview,state.draftBackPreview)}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                CaptureStatus("Front captured")
                AnimatedVisibility(hasBack,enter=expandHorizontally(DockMotion.spec(220))+fadeIn(DockMotion.spec(140)),exit=shrinkHorizontally(DockMotion.spec(160))+fadeOut(DockMotion.spec(80))) {CaptureStatus("Back captured")}
            }
            ActionRow(Icons.Outlined.PhotoCamera,if(hasBack) "Retake back" else "Photograph the back","Capture the other side before reading",!busy,onBackCamera)
            ActionRow(Icons.Outlined.Image,if(hasBack) "Replace back image" else "Import the back","Choose a photo from your device",!busy) {gallery.launch("image/*")}
        }
        Surface(tonalElevation=2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                DockButton(onClick={vm.readCapturedSides(waitForPresentation=true)},enabled=!busy && state.draftPreview!=null,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                    Icon(Icons.Outlined.DocumentScanner,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text(if(hasBack) "Read both sides" else "Continue with front")
                }
                DockTextButton(onClick=vm::reviewCapturedManually,enabled=!busy) {Text("Enter details instead")}
            }
        }
    }
}

@Composable private fun SectionTitle(icon:ImageVector,title:String) {
    Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
        Text(title,style=MaterialTheme.typography.titleLarge)
    }
}

private class PhoneEditorRow(initial:PhoneNumber,initialOrdinal:Int,val enter:Boolean) {
    val id=java.util.UUID.randomUUID().toString()
    var phone by mutableStateOf(initial)
    var ordinal by mutableIntStateOf(initialOrdinal)
    val visibility=MutableTransitionState(!enter).apply {targetState=true}
}

/** Keep the departing row until collapse completes so following fields move with it. */
@Composable private fun PhoneEditor(cardId:String,phones:List<PhoneNumber>,busy:Boolean,validationShown:Boolean,onChange:(List<PhoneNumber>)->Unit) {
    val rows=remember(cardId) {mutableStateListOf<PhoneEditorRow>().apply {phones.forEachIndexed {i,phone->add(PhoneEditorRow(phone,i+1,false))}}}
    LaunchedEffect(phones) {
        val active=rows.filter {it.visibility.targetState}
        phones.forEachIndexed {i,phone->
            if(i<active.size) {active[i].phone=phone;active[i].ordinal=i+1}
            else rows.add(PhoneEditorRow(phone,i+1,true))
        }
        active.drop(phones.size).forEach {it.visibility.targetState=false}
    }
    fun values()=rows.filter {it.visibility.targetState}.map {it.phone}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        rows.forEach {row->key(row.id) {
            val request=remember {FocusRequester()}
            val bring=remember {BringIntoViewRequester()}
            LaunchedEffect(row.id) {if(row.enter) {withFrameNanos {};if(row.visibility.targetState) {request.requestFocus();bring.bringIntoView()}}}
            LaunchedEffect(row.id) {
                snapshotFlow {row.visibility.isIdle && !row.visibility.currentState && !row.visibility.targetState}.first {it}
                rows.remove(row)
            }
            AnimatedVisibility(row.visibility,enter=expandVertically(DockMotion.settle(460f,.95f))+fadeIn(DockMotion.spec(130)),exit=shrinkVertically(DockMotion.spec(180))+fadeOut(DockMotion.spec(90)),modifier=if(row.visibility.targetState) Modifier else Modifier.clearAndSetSemantics {}) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Phone ${row.ordinal}",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        DockIconButton(enabled=!busy && row.visibility.targetState,onClick={row.visibility.targetState=false;onChange(values())}) {Icon(Icons.Outlined.RemoveCircleOutline,"Remove phone ${row.ordinal}")}
                    }
                    val issue=validationShown && splitPhoneNumbers(row.phone.number).size>1
                    DockOutlinedTextField(row.phone.number,{row.phone=row.phone.copy(number=it.take(80));onChange(values())},Modifier.fillMaxWidth().focusRequester(request).bringIntoViewRequester(bring),enabled=!busy && row.visibility.targetState,label={Text("Number ${row.ordinal}")},singleLine=true,isError=issue,supportingText={AnimatedVisibility(issue,enter=expandVertically(DockMotion.spec(180)),exit=shrinkVertically(DockMotion.spec(140))) {Text("Keep one phone number in each field.")}},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone))
                    DockOutlinedTextField(row.phone.label,{row.phone=row.phone.copy(label=it.take(40));onChange(values())},Modifier.fillMaxWidth(),enabled=!busy && row.visibility.targetState,label={Text("Label ${row.ordinal} (optional)")},singleLine=true)
                }
            }
        }}
        DockTextButton(enabled=!busy && phones.size<12,onClick={onChange(values()+PhoneNumber(""))}) {Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Add number")}
    }
}

@Composable private fun CaptureStatus(label:String) {
    Surface(color=MaterialTheme.colorScheme.secondaryContainer,contentColor=MaterialTheme.colorScheme.onSecondaryContainer,shape=MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.CheckCircle,null,Modifier.size(16.dp));Text(label,style=MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun DisclosureChevron(expanded:Boolean) {
    val angle=animateFloatAsState(if(expanded) 180f else 0f,DockMotion.settle(550f,.95f),label="Disclosure direction")
    Icon(Icons.Outlined.ExpandMore,null,Modifier.graphicsLayer {rotationZ=angle.value})
}

/** A persisted card docks into its receipt; this runs only after the repository confirms save. */
@Composable private fun SavedCardReceipt(card:Card?,vm:VaultViewModel,name:String,event:Long) {
    val arrival=remember(event) {Animatable(if(android.animation.ValueAnimator.areAnimatorsEnabled()) 0f else 1f)}
    LaunchedEffect(event) {arrival.animateTo(1f,DockMotion.settle(440f,.82f))}
    val photo=if(card!=null && card.hasFrontImage) cardPhoto(card,vm).bitmap else null
    val accent=MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),contentAlignment=Alignment.TopCenter) {
        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.large) {
            Row(Modifier.padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(photo!=null) Image(photo.asImageBitmap(),null,Modifier.size(width=62.dp,height=40.dp).graphicsLayer {
                    val travel=1f-arrival.value
                    translationX=travel*20*density;rotationZ=-travel*9f;scaleX=1f+travel*.12f;scaleY=scaleX
                }.clip(MaterialTheme.shapes.small),contentScale=ContentScale.Fit)
                Canvas(Modifier.size(24.dp)) {
                    val progress=arrival.value.coerceIn(0f,1f)
                    val start=Offset(size.width*.12f,size.height*.52f)
                    val bend=Offset(size.width*.4f,size.height*.78f)
                    val end=Offset(size.width*.92f,size.height*.2f)
                    val first=(progress/.35f).coerceIn(0f,1f)
                    drawLine(accent,start,start+(bend-start)*first,2.5.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)
                    if(progress>.35f) drawLine(accent,bend,bend+(end-bend)*((progress-.35f)/.65f),2.5.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)
                }
                Column(Modifier.weight(1f,false)) {Text("Card saved",style=MaterialTheme.typography.titleMedium);Text(name,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)}
            }
        }
    }
}

@Composable private fun FavoriteControl(card:Card,busy:Boolean,onClick:()->Unit) {
    val rotation=animateFloatAsState(if(card.favorite) 0f else -24f,DockMotion.spec(220),label="Favorite turn")
    val size=animateFloatAsState(if(card.favorite) 1.12f else 1f,DockMotion.spec(220),label="Favorite emphasis")
    DockIconToggleButton(checked=card.favorite,onCheckedChange={onClick()},enabled=!busy) {
        Icon(if(card.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,if(card.favorite) "Remove ${card.displayLabel} from favorites" else "Favorite ${card.displayLabel}",modifier=Modifier.graphicsLayer {rotationZ=rotation.value;scaleX=size.value;scaleY=size.value},tint=MaterialTheme.colorScheme.primary)
    }
}
