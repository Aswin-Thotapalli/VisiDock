@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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

private data class Destination(val title: String, val icon: ImageVector)
private data class ScreenSnapshot(val route: String, val card: Card?=null, val draft: Card?=null, val preview: String?=null)
private val LocalSharedScope=staticCompositionLocalOf<SharedTransitionScope?> {null}
private val LocalScreenScope=staticCompositionLocalOf<AnimatedVisibilityScope?> {null}
private val destinations = listOf(Destination("Collection", Icons.Outlined.Style), Destination("Favorites", Icons.Outlined.StarOutline), Destination("Settings", Icons.Outlined.Tune))

@Composable fun VaultScreen(vm: VaultViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var celebration by remember { mutableStateOf(false) }
    var observedSave by remember { mutableLongStateOf(state.savedEvent) }
    LaunchedEffect(state.savedEvent) { if(state.savedEvent>observedSave) {observedSave=state.savedEvent;celebration=true;kotlinx.coroutines.delay(1800);celebration=false} }
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var add by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::scan) }
    var capture by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.message) { state.message?.let {
        // Confirmed saves already have their own visual receipt; avoid duplicate overlays.
        if(it!="Card saved") snackbar.showSnackbar(it)
        vm.clearMessage()
    } }
    val selected = state.cards.firstOrNull { it.id == state.selectedId }
    BackHandler(state.draft != null || state.selectedId != null || tab != 0) {
        when { state.draft != null -> discard=true; state.selectedId != null -> vm.select(null); else -> tab=0 }
    }
    if(capture!=null) {
        val back=capture==true
        CaptureScreen(back, vm::cameraFile, { success -> vm.cameraResult(success,back); if(success) capture=null }, {vm.cameraResult(false); capture=null})
        return
    }
    SharedTransitionLayout {
    val sharedScope=this
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        val rail = maxWidth >= 600.dp
        val showNav = state.session != null && state.draft == null && (expanded || selected == null)
        Scaffold(
            snackbarHost={ SnackbarHost(snackbar) },
            bottomBar={ if(showNav && !rail) NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                destinations.forEachIndexed { index, item -> NavigationBarItem(selected=tab==index, onClick={ tab=index; vm.select(null) }, icon={ Icon(item.icon, null) }, label={ Text(item.title) }) }
            } },
            floatingActionButton={ if(showNav && tab < 2 && state.busy == null) ExtendedFloatingActionButton(elevation=FloatingActionButtonDefaults.elevation(0.dp,0.dp,0.dp,0.dp), onClick={ add=true }, modifier=Modifier.semantics { contentDescription="Add card" }, icon={ Icon(Icons.Outlined.Add, null) }, text={ Text("Add card") }, containerColor=MaterialTheme.colorScheme.primary, contentColor=MaterialTheme.colorScheme.onPrimary) }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if(showNav && rail) NavigationRail(Modifier.fillMaxHeight(), containerColor=MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(20.dp)); Icon(Icons.Outlined.Style, "VisiDock", tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(32.dp))
                    destinations.forEachIndexed { index, item -> NavigationRailItem(selected=tab==index, onClick={tab=index; vm.select(null)}, icon={ Icon(item.icon, null) }, label={ Text(item.title) }) }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedVisibility(state.busy != null, enter=fadeIn(tween(120)), exit=fadeOut(tween(90))) {
                        Column(Modifier.fillMaxWidth().semantics { liveRegion=LiveRegionMode.Polite }) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.busy.orEmpty(), Modifier.padding(horizontal=24.dp, vertical=8.dp), style=MaterialTheme.typography.bodySmall)
                            if(state.busy?.startsWith("Downloading visual reading")==true) TextButton(onClick=vm::cancelOperation,modifier=Modifier.padding(horizontal=12.dp)) {Text("Cancel download")}
                        }
                    }
                    state.error?.let { error ->
                        Surface(color=MaterialTheme.colorScheme.errorContainer) {
                            Row(Modifier.fillMaxWidth().padding(start=20.dp), verticalAlignment=Alignment.CenterVertically) {
                                Text(error, Modifier.weight(1f).padding(vertical=12.dp).semantics { liveRegion=LiveRegionMode.Assertive }, color=MaterialTheme.colorScheme.onErrorContainer, style=MaterialTheme.typography.bodyMedium)
                                IconButton(onClick=vm::clearError) { Icon(Icons.Outlined.Close, "Dismiss error") }
                            }
                        }
                    }
                    val route = when {
                        !state.configured -> "setup"
                        state.session == null -> "auth"
                        state.draft != null -> "editor"
                        tab == 2 -> "settings"
                        selected != null && !expanded -> "detail"
                        else -> "collection"
                    }
                    AnimatedContent(ScreenSnapshot(route,selected,state.draft,state.draftPreview), contentKey={it.route}, modifier=Modifier.weight(1f), label="Screen transition", transitionSpec={
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260, easing=FastOutSlowInEasing)) { if(targetState.route=="collection") -it/12 else it/12 }) togetherWith
                            (fadeOut(tween(110)) + slideOutHorizontally(tween(180)) { if(targetState.route=="collection") it/16 else -it/16 })
                    }) { screen -> CompositionLocalProvider(LocalSharedScope provides if(expanded) null else sharedScope, LocalScreenScope provides this) { when(screen.route) {
                        "setup" -> SetupScreen()
                        "auth" -> AuthScreen(state.busy != null, vm::signIn, vm::resetPassword)
                        "editor" -> if(screen.draft != null) EditorScreen(state.copy(draft=screen.draft,draftPreview=screen.preview), vm, onBack={discard=true},onCaptureBack={capture=true})
                        "settings" -> SettingsScreen(state, vm)
                        "detail" -> if(screen.card != null) DetailScreen(screen.card, vm, state.busy != null, onBack={vm.select(null)})
                        else -> Row(Modifier.fillMaxSize()) {
                            CollectionScreen(state, query, {query=it}, tab==1, vm, Modifier.weight(1f), onAdd={add=true})
                            if(expanded) {
                                VerticalDivider()
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    if(selected != null) DetailScreen(selected, vm, state.busy != null, onBack={vm.select(null)})
                                    else EmptyState(Icons.Outlined.Badge, "A little context.\nA lasting connection.", "Select a card to see the person, the details and where your paths crossed.", Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    } } }
                }
            }
        }
    }
    }
    if(state.busy!=null && state.draftPreview!=null && !state.busy!!.startsWith("Downloading") && (state.busy!!.contains("Reading",ignoreCase=true) || state.busy!!.contains("Identifying",ignoreCase=true))) ReadingStage(state.draftPreview!!,state.busy!!,vm::cancelOperation)
    AnimatedVisibility(celebration,enter=fadeIn(tween(140))+slideInVertically(tween(260)) {-it/2},exit=fadeOut(tween(180))) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),contentAlignment=Alignment.TopCenter) {Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.large) {Row(Modifier.padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Icon(Icons.Outlined.CheckCircle,null);Column {Text("Connection kept.",style=MaterialTheme.typography.titleMedium);Text(state.savedName,style=MaterialTheme.typography.bodyMedium)}}}}
    }
    if(add) ModalBottomSheet(onDismissRequest={add=false}) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=32.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Keep the connection", style=MaterialTheme.typography.headlineMedium)
            Text("Capture a card. Add the context that matters.", color=MaterialTheme.colorScheme.onSurfaceVariant, modifier=Modifier.padding(bottom=16.dp))
            ActionRow(Icons.Outlined.PhotoCamera, "Take a photo", "Use your camera to capture a visiting card") {
                add=false
                capture=false
            }
            ActionRow(Icons.Outlined.Image, "Import an image", "Choose a JPEG, PNG or WebP, up to 20 MB") { add=false; gallery.launch("image/*") }
            ActionRow(Icons.Outlined.Edit, "Enter details", "Save a connection without a card photo") { add=false; vm.newCard() }
        }
    }
    if(discard) AlertDialog(onDismissRequest={discard=false}, title={Text("Discard your changes?")}, text={Text("The unsaved details and photo will be removed from this device.")},
        confirmButton={TextButton(enabled=state.busy==null, onClick={vm.discard(); discard=false}) {Text("Discard changes")}}, dismissButton={TextButton(onClick={discard=false}) {Text("Keep editing")}})
}

@Composable private fun CollectionScreen(state: VaultState, query: String, onQuery: (String)->Unit, favorites: Boolean, vm: VaultViewModel, modifier: Modifier, onAdd: ()->Unit) {
    var results by remember { mutableStateOf<List<Card>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var semantic by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    LaunchedEffect(state.cards, query, favorites) {
        val cards=state.cards.filter { !favorites || it.favorite }
        results=withContext(Dispatchers.Default) { CardLogic.search(cards,query) }
        semantic=false; unavailable=false
        if(query.isNotBlank()) {
            searching=true
            try {
                kotlinx.coroutines.delay(180)
                val found=vm.search(cards,query)
                results=found.cards; semantic=found.semantic; unavailable=found.unavailable
            } finally { searching=false }
        }
    }
    BoxWithConstraints(modifier.fillMaxHeight()) {
    val compactHeader=maxHeight < 500.dp || LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=16.dp), verticalAlignment=Alignment.CenterVertically) {
            Image(painterResource(R.drawable.visidock_mark),null,Modifier.size(32.dp).clip(MaterialTheme.shapes.small).background(Color(0xFF071D49)))
            Text("VisiDock", Modifier.padding(start=8.dp).weight(1f), style=MaterialTheme.typography.titleLarge)
            if(BuildConfig.DEMO) SuggestionChip(onClick={}, label={Text("Demo")})
        }
        Text(if(favorites) "Keep them close." else state.session?.displayName?.takeIf {it.isNotBlank()}?.let {"Your next conversation, ${it.substringBefore(' ')}."} ?: "Your next conversation.", Modifier.padding(horizontal=24.dp), style=if(compactHeader) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(!compactHeader) Text(if(favorites) "Your people, a little easier to find." else if(state.cards.isEmpty()) "Good meetings deserve a second chapter." else "${state.cards.size} people. Plenty to pick up on.", Modifier.padding(horizontal=24.dp, vertical=8.dp), color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=12.dp), singleLine=true,
            shape=MaterialTheme.shapes.large, leadingIcon={Icon(Icons.Outlined.Search, null)}, placeholder={Text("Name, company or a memory…",maxLines=1,overflow=TextOverflow.Ellipsis)},
            trailingIcon={if(query.isNotEmpty()) IconButton(onClick={onQuery("")}) {Icon(Icons.Outlined.Close, "Clear search")}},
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search))
        if(query.isNotBlank()) Text(when {searching -> "Finding connections on your device…"; unavailable -> "Smart search unavailable · showing keyword matches"; semantic -> "On-device smart search · related matches may vary"; else -> "Matching card details and notes"}, Modifier.padding(horizontal=24.dp).padding(bottom=8.dp).semantics {liveRegion=LiveRegionMode.Polite}, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=12.dp), horizontalArrangement=Arrangement.SpaceBetween) {
            Text(if(query.isNotBlank()) "Search results" else if(favorites) "Favorites" else "Your collection", style=MaterialTheme.typography.titleMedium)
            Text("${results.size} ${if(results.size==1) "card" else "cards"}", color=MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodyMedium)
        }
        when {
            state.loading -> Column(Modifier.padding(24.dp), verticalArrangement=Arrangement.spacedBy(24.dp)) { repeat(4) { Surface(Modifier.fillMaxWidth().height(70.dp), color=MaterialTheme.colorScheme.surfaceVariant, shape=MaterialTheme.shapes.medium) {} } }
            results.isEmpty() -> EmptyState(if(query.isNotBlank()) Icons.Outlined.SearchOff else Icons.Outlined.Style,
                if(query.isNotBlank()) "No connections found" else if(favorites) "Keep your go-to people close" else "Your next conversation starts here.",
                if(query.isNotBlank()) "Try fewer words, a company name or something you wrote in your notes." else if(favorites) "Tap the star on a card to keep it here." else "Add your first visiting card. We’ll help you remember the person behind it.",
                Modifier.weight(1f), action=if(!favorites && query.isBlank()) onAdd else null, actionLabel="Add your first card")
            else -> LazyColumn(Modifier.weight(1f), contentPadding=PaddingValues(start=24.dp, end=24.dp, bottom=104.dp)) {
                items(results, key={it.id}) { card ->
                    Column(Modifier.animateItem(fadeInSpec=tween(160), placementSpec=tween(220, easing=FastOutSlowInEasing), fadeOutSpec=tween(100))) {
                        CardRow(card, vm, state.selectedId==card.id, {vm.select(card)}, {vm.favorite(card)}, state.busy!=null)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
    }
}

@Composable private fun CardRow(card: Card, vm: VaultViewModel, selected: Boolean, onClick: ()->Unit, onFavorite: ()->Unit, busy: Boolean) {
    val interactions=remember {MutableInteractionSource()}
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) .985f else 1f,tween(140),label="Card lift")
    Surface(onClick=onClick, interactionSource=interactions, modifier=Modifier.graphicsLayer {scaleX=scale;scaleY=scale}, color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape=MaterialTheme.shapes.large) {
        Column {
            if(card.imagePath.isNotBlank()) CollectionPhoto(card,vm)
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                if(card.imagePath.isBlank()) Avatar(card,48)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(card.displayLabel,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(listOf(card.role,card.company.takeIf {card.name.isNotBlank()}.orEmpty()).filter(String::isNotBlank).joinToString(" · ").ifBlank {if(card.name.isBlank()) "Company card" else "A new thread to follow."},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(card.notes.isNotBlank()) Text(card.notes,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                IconToggleButton(checked=card.favorite,onCheckedChange={onFavorite()},enabled=!busy) {Icon(if(card.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,if(card.favorite) "Remove ${card.displayLabel} from favorites" else "Favorite ${card.displayLabel}",tint=MaterialTheme.colorScheme.primary)}
            }
        }
    }
}

@Composable private fun cardImageTransition(id:String):Modifier {
    val shared=LocalSharedScope.current
    val screen=LocalScreenScope.current
    return if(shared!=null && screen!=null) with(shared) {
        Modifier.sharedElement(rememberSharedContentState("card-photo-$id"),animatedVisibilityScope=screen,boundsTransform={_,_->tween(if(android.animation.ValueAnimator.areAnimatorsEnabled()) 360 else 0,easing=FastOutSlowInEasing)})
    } else Modifier
}

@Composable private fun CollectionPhoto(card:Card,vm:VaultViewModel) {
    val photo by produceState<android.graphics.Bitmap?>(null,card.id,card.imagePath) {
        value=withContext(Dispatchers.IO) {runCatching {vm.photo(card)?.let {BitmapFactory.decodeByteArray(it,0,it.size)}}.getOrNull()}
    }
    Box(cardImageTransition(card.id).fillMaxWidth().height(156.dp).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
        if(photo!=null) Image(photo!!.asImageBitmap(),null,Modifier.fillMaxSize().padding(16.dp),contentScale=ContentScale.Fit)
        else Icon(Icons.Outlined.Style,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
        if(card.backImagePath.isNotBlank()) Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp),color=MaterialTheme.colorScheme.surface,shape=CircleShape) {Text("2 sides",Modifier.padding(horizontal=10.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)}
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
    Surface(onClick=onClick, enabled=enabled, shape=MaterialTheme.shapes.medium, color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(vertical=16.dp, horizontal=8.dp), horizontalArrangement=Arrangement.spacedBy(16.dp), verticalAlignment=Alignment.CenterVertically) {
            Icon(icon, null, tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style=MaterialTheme.typography.titleMedium); Text(subtitle, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}

@Composable private fun Photo(card: Card, vm: VaultViewModel, local: String?=null, localBack: String?=null) {
    val hasFront=local!=null || card.imagePath.isNotBlank()
    val hasBack=localBack!=null || card.backImagePath.isNotBlank()
    if(!hasFront) return
    var back by rememberSaveable(card.id) { mutableStateOf(false) }
    var retry by remember(card.id) { mutableIntStateOf(0) }
    val angle by animateFloatAsState(if(back && hasBack) 180f else 0f,
        tween(if(android.animation.ValueAnimator.areAnimatorsEnabled()) 520 else 0,easing=androidx.compose.animation.core.CubicBezierEasing(0.22f,0.7f,0.15f,1f)),label="Turn the card")
    val visibleBack=angle>90f
    data class LoadedPhoto(val bitmap:android.graphics.Bitmap?=null,val failed:Boolean=false,val loading:Boolean=true)
    @Composable fun loadPhoto(path:String?,side:Boolean):LoadedPhoto {
        val result by produceState(LoadedPhoto(),card.id,path,side,retry) {
            value=try {LoadedPhoto(withContext(Dispatchers.IO) {
                if(path!=null) BitmapFactory.decodeFile(path) else vm.photo(card,side)?.let {BitmapFactory.decodeByteArray(it,0,it.size)}
            },loading=false)} catch(e:kotlinx.coroutines.CancellationException) {throw e} catch(e:Exception) {LoadedPhoto(failed=true,loading=false)}
        }
        return result
    }
    val frontPhoto=loadPhoto(local,false)
    val backPhoto=if(hasBack) loadPhoto(localBack,true) else LoadedPhoto(loading=false)
    val shown=if(visibleBack) backPhoto else frontPhoto
    val bitmap=shown.bitmap
    val loading=shown.loading
    val error=shown.failed || (!loading && bitmap==null)
    val navy=androidx.compose.ui.graphics.Color(0xFF10264B)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.large,
            modifier=cardImageTransition(card.id).fillMaxWidth().graphicsLayer {
                rotationY=angle; cameraDistance=16*density
                shadowElevation=if(angle>1f && angle<179f) 12*density else 0f
                ambientShadowColor=navy; spotShadowColor=navy
                shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp); clip=true
            }) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.65f).graphicsLayer {rotationY=if(visibleBack) 180f else 0f},contentAlignment=Alignment.Center) {
                when {
                    bitmap!=null -> Image(bitmap!!.asImageBitmap(),"${if(visibleBack) "Back" else "Front"} of visiting card for ${card.displayLabel}",Modifier.fillMaxSize().padding(12.dp),contentScale=ContentScale.Fit)
                    error -> TextButton(onClick={retry++}) {Text("Image unavailable · Retry")}
                    loading -> CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
        if(hasBack) TextButton(onClick={back=!back},modifier=Modifier.align(Alignment.CenterHorizontally).semantics {stateDescription=if(back) "Showing back" else "Showing front"}) {
            Icon(Icons.Outlined.Flip,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(back) "Turn to front" else "Turn to back")
        }
    }
}

@Composable private fun DetailScreen(card: Card, vm: VaultViewModel, busy: Boolean, onBack: ()->Unit) {
    val context=LocalContext.current
    var deleting by rememberSaveable(card.id) { mutableStateOf(false) }
    var contact by rememberSaveable(card.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title={Text("Connection")}, navigationIcon={IconButton(onClick=onBack) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to collection")}}, actions={
            IconButton(enabled=!busy, onClick={vm.favorite(card)}) {Icon(if(card.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline, "Toggle favorite")}
            IconButton(enabled=!busy, onClick={vm.edit(card)}) {Icon(Icons.Outlined.Edit,"Edit card")}
        })
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Photo(card, vm)
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(card.displayLabel, style=MaterialTheme.typography.headlineLarge); Text(listOf(card.role,card.company.takeIf {card.name.isNotBlank()}.orEmpty()).filter(String::isNotBlank).joinToString("\n"), style=MaterialTheme.typography.bodyLarge, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                DockButton(enabled=!busy, onClick={contact=true}) {Icon(Icons.Outlined.PersonAdd,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add to contacts")}
                OutlinedButton(enabled=!busy, onClick={vm.edit(card)}) {Text("Edit details")}
            }
            HorizontalDivider()
            listOf("Phone" to card.phone, "Email" to card.email, "Website" to card.website, "Address" to card.address).filter {it.second.isNotBlank()}.forEach { (label,value) ->
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant); androidx.compose.foundation.text.selection.SelectionContainer {Text(value,style=MaterialTheme.typography.bodyLarge)} }
                    IconButton(onClick={
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
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {Text("Where you left off",style=MaterialTheme.typography.titleMedium); Text(card.notes.ifBlank {"Where did you meet? Add a note to remember the moment."},color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if(card.rawText.isNotBlank() || card.backRawText.isNotBlank()) { var show by rememberSaveable(card.id) {mutableStateOf(false)}
                TextButton(onClick={show=!show}) {Text(if(show) "Hide recognized text" else "View recognized text"); Icon(if(show) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
                AnimatedVisibility(show) {Text(listOf(card.rawText,card.backRawText).filter(String::isNotBlank).joinToString("\n\nBack of card\n"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Text("Saved ${CardLogic.date(card.createdAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled=!busy,onClick={deleting=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Icon(Icons.Outlined.DeleteOutline,null); Spacer(Modifier.width(8.dp)); Text("Delete card")}
        }
    }
    if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text("Delete ${card.displayLabel}?")},text={Text("The saved details, original image and preview will be deleted. This cannot be undone.")},confirmButton={TextButton(onClick={deleting=false;vm.delete(card)}) {Text("Delete permanently")}},dismissButton={TextButton(onClick={deleting=false}) {Text("Keep card")}})
    if(contact) AlertDialog(onDismissRequest={contact=false},title={Text("Add to your phone contacts?")},text={Text("Your contacts app opens for review. Choose the destination account and check for an existing contact before saving. VisiDock does not read your phone contacts.")},confirmButton={TextButton(onClick={
        contact=false
        val intent=Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
            putExtra(ContactsContract.Intents.Insert.NAME,card.name); putExtra(ContactsContract.Intents.Insert.PHONE,card.phone)
            putExtra(ContactsContract.Intents.Insert.EMAIL,card.email); putExtra(ContactsContract.Intents.Insert.COMPANY,card.company)
            putExtra(ContactsContract.Intents.Insert.JOB_TITLE,card.role); putExtra(ContactsContract.Intents.Insert.POSTAL,card.address)
            putExtra(ContactsContract.Intents.Insert.NOTES,card.notes)
            val data=arrayListOf(android.content.ContentValues().apply {put(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE);put(ContactsContract.CommonDataKinds.Website.URL,card.website)})
            if(card.website.isNotBlank()) putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA,data)
        }
        runCatching {context.startActivity(intent)}.onFailure {vm.report("No contacts app is available on this device.")}
    }) {Text("Open contacts")}},dismissButton={TextButton(onClick={contact=false}) {Text("Cancel")}})
}

@Composable private fun EditorScreen(state: VaultState, vm: VaultViewModel, onBack: ()->Unit, onCaptureBack: ()->Unit) {
    val card=checkNotNull(state.draft)
    val busy=state.busy!=null
    val existing=state.cards.any {it.id==card.id}
    val duplicate=CardLogic.duplicates(card,state.cards)
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val backGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::scanBack) }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title={Text(if(existing) "Edit connection" else "Review your card",maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(enabled=!busy,onClick=onBack) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Cancel editing")}},actions={
            // Keep the commit target stationary while Android animates the keyboard.
            DockButton(enabled=!busy && card.displayLabel.isNotBlank(),onClick={focus.clearFocus();keyboard?.hide();vm.save()},modifier=Modifier.padding(end=12.dp)) {
                Text(if(existing) "Save changes" else if(state.remainingPeople>0) "Save & next" else "Save card",maxLines=1)
            }
        })
        Column(Modifier.weight(1f).widthIn(max=720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(if(existing) "Keep the connection current." else "The card, decoded. Your call.",style=MaterialTheme.typography.titleLarge)
            if(!existing) Text("Check the details against the photo. Text recognition can make mistakes.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Photo(card,vm,state.draftPreview,state.draftBackPreview)
            if(!existing && state.draftPreview!=null) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!busy,onClick=onCaptureBack) {Icon(Icons.Outlined.Flip,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp));Text(if(state.draftBackPreview==null) "Scan the back" else "Rescan back")}
                    TextButton(enabled=!busy,onClick={backGallery.launch("image/*")}) {Text("Import back")}
                }
                if(state.visualModelReady) TextButton(enabled=!busy,onClick=vm::readPhotosAgain) {Text("Read photos again")}
                else TextButton(enabled=!busy,onClick=vm::downloadVisualModel) {Text("Download visual reading · 2.6 GB")}
                Text("Reading both sides replaces the current suggestions. Add the back before editing.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(state.remainingPeople>0) {
                Text("${state.remainingPeople + 1} people to review · each saves as a separate contact",style=MaterialTheme.typography.titleMedium)
                TextButton(enabled=!busy,onClick={focus.clearFocus();keyboard?.hide();vm.skipPerson()}) {Text("Skip person")}
            }
            if(state.extractionWarnings.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Check against the card",style=MaterialTheme.typography.titleMedium)
                    state.extractionWarnings.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                }
            }
            if(duplicate.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {Text("Possible duplicate: ${duplicate.joinToString {it.name}}. This email or phone is already in your collection.",Modifier.padding(16.dp))}
            fun change(value: Card)=vm.changeDraft(value)
            @Composable fun field(label: String,value: String,limit: Int,type: KeyboardType=KeyboardType.Text,multi: Boolean=false,update:(String)->Unit) {
                OutlinedTextField(value,{update(it.take(limit))},Modifier.fillMaxWidth(),enabled=!busy,label={Text(label)},singleLine=!multi,minLines=if(multi) 3 else 1,keyboardOptions=KeyboardOptions(keyboardType=type,capitalization=if(type==KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None))
            }
            Text("Who’s on the card",style=MaterialTheme.typography.titleLarge)
            Text("A person or company name is enough to keep this card.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            field("Full name",card.name,200) {change(card.copy(name=it))}
            field("Job title",card.role,300) {change(card.copy(role=it))}
            field("Company",card.company,300) {change(card.copy(company=it))}
            Spacer(Modifier.height(4.dp))
            Text("Ways to reconnect",style=MaterialTheme.typography.titleLarge)
            field("Phone",card.phone,100,KeyboardType.Phone) {change(card.copy(phone=it))}
            field("Email",card.email,300,KeyboardType.Email) {change(card.copy(email=it))}
            field("Website",card.website,300,KeyboardType.Uri) {change(card.copy(website=it))}
            field("Address",card.address,1000,multi=true) {change(card.copy(address=it))}
            Text("Leave yourself a thread",style=MaterialTheme.typography.titleMedium)
            field("Where you met, what you talked about…",card.notes,4000,multi=true) {change(card.copy(notes=it))}
            if(card.rawText.isNotBlank()) {
                var source by rememberSaveable(card.id) {mutableStateOf(false)}
                TextButton(onClick={source=!source}) {Icon(Icons.Outlined.DocumentScanner,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(source) "Hide original reading" else "Compare with original reading")}
                AnimatedVisibility(source) { Column {Text(card.rawText,style=MaterialTheme.typography.bodyMedium); if(card.backRawText.isNotBlank()) {Spacer(Modifier.height(12.dp)); Text("Back of card",style=MaterialTheme.typography.titleSmall);Text(card.backRawText,style=MaterialTheme.typography.bodyMedium)}} }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun SettingsScreen(state: VaultState,vm: VaultViewModel) {
    val context=LocalContext.current
    var delete by rememberSaveable {mutableStateOf(false)}
    var learningHistory by rememberSaveable {mutableStateOf(false)}
    var password by remember {mutableStateOf("")}
    val busy=state.busy!=null
    Column(Modifier.widthIn(max=720.dp).fillMaxHeight().fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("Make it yours.",style=MaterialTheme.typography.headlineLarge)
        Text(state.session?.email.orEmpty(),color=MaterialTheme.colorScheme.onSurfaceVariant)
        var profileName by remember(state.session?.displayName) { mutableStateOf(state.session?.displayName.orEmpty()) }
        OutlinedTextField(profileName,{profileName=it.take(100)},Modifier.fillMaxWidth(),label={Text("What should we call you?")},singleLine=true)
        TextButton(enabled=!busy && profileName.isNotBlank(),onClick={vm.updateDisplayName(profileName)}) { Text("Save profile name") }
        if(BuildConfig.DEMO) Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {Text("Demo workspace\nThese are fictional contacts. Changes are held in memory and reset when the app process restarts. Use sample images only.",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onPrimaryContainer)}
        if(!BuildConfig.DEMO && state.session?.verified==false) {
            Text("Verify your email",style=MaterialTheme.typography.titleMedium)
            Text("Confirm your address to help protect access to your account.")
            OutlinedButton(enabled=!busy,onClick=vm::verifyEmail) {Text("Send verification email")}
            TextButton(enabled=!busy,onClick=vm::refreshSession) {Text("I’ve verified my email")}
            TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://visidock-thotapalli.web.app/auth")))}.onFailure {vm.report("Open visidock-thotapalli.web.app/auth in your browser for verification help.")}}) {Text("Verification help")}
        }
        HorizontalDivider()
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learn from my corrections",style=MaterialTheme.typography.titleMedium)
                Text("Saved corrections help recognition adapt privately in the background. Learning data stays on this device.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=state.learningEnabled,onCheckedChange=vm::setLearning,enabled=!busy)
        }
        TextButton(enabled=!busy,onClick={learningHistory=true;vm.inspectLearning()}) {Text("View or clear learning history")}
        Text("Read the whole card",style=MaterialTheme.typography.titleLarge)
        Text(if(state.visualModelReady) "Visual reading is installed. It looks at card photographs on this device and can propose more than one person. Always review the results." else "Download the visual model once (2.6 GB). It reads the layout and people on the card without sending photographs to an AI service. Use Wi-Fi and allow at least 3.1 GB free space.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!state.visualModelReady) OutlinedButton(enabled=!busy,onClick=vm::downloadVisualModel) { Text("Download visual reading") }
        HorizontalDivider()
        Text("Designed around your privacy",style=MaterialTheme.typography.titleLarge)
        Text("Text recognition runs on your device. In the connected app, card details are stored in your private Firebase collection and photographs in private Cloudflare R2 storage. Cloud storage is not end-to-end encrypted.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Smart search uses an English-language model bundled with VisiDock. Card details, notes and search queries are processed on this device. No model provider receives them. Related matches are suggestions, not facts.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Contacts are added only when you choose to open the phone’s contact editor and save there.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        ActionRow(Icons.Outlined.Sync,"Retry sync","Reload cards and retry pending image cleanup",!busy,vm::retry)
        if(!BuildConfig.DEMO) {
            ActionRow(Icons.Outlined.LockReset,"Reset password","Send a reset link to your email",!busy) {vm.resetPassword(state.session?.email.orEmpty())}
            OutlinedButton(enabled=!busy,onClick=vm::signOut) {Text("Sign out")}
            TextButton(enabled=!busy,onClick={delete=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("Delete account and all cards")}
        }
        Text("VisiDock ${BuildConfig.VERSION_NAME} · ${if(BuildConfig.DEMO) "Demo" else "Cloud"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(learningHistory) AlertDialog(onDismissRequest={learningHistory=false},title={Text("Learned on this device")},text={
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(state.learnedLabels.isEmpty()) Text("No corrections remembered yet. Edits are learned after you save a scanned card, when the corrected text is present in its OCR.")
            state.learnedLabels.forEach {Text("${it.phrase} · ${it.field}")}
        }
    },confirmButton={TextButton(onClick={learningHistory=false}) {Text("Done")}},dismissButton={TextButton(enabled=!busy && state.learnedLabels.isNotEmpty(),onClick=vm::clearLearning) {Text("Clear history")}})
    if(delete) AlertDialog(onDismissRequest={delete=false;password=""},title={Text("Delete your account?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("All saved cards and images will be permanently removed before your account is deleted. Enter your password to confirm.")
        OutlinedTextField(password,{password=it},label={Text("Current password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
    }},confirmButton={TextButton(enabled=password.isNotBlank(),onClick={val value=password;password="";delete=false;vm.deleteAccount(value)}) {Text("Delete permanently")}},dismissButton={TextButton(onClick={delete=false;password=""}) {Text("Keep account")}})
}

@Composable private fun AuthScreen(busy: Boolean,onSubmit:(String,String,Boolean,String)->Unit,onReset:(String)->Unit) {
    var email by rememberSaveable {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var register by rememberSaveable {mutableStateOf(false)}
    var profileName by rememberSaveable {mutableStateOf("")}
    var visible by remember {mutableStateOf(false)}
    Box(Modifier.fillMaxSize().imePadding(),contentAlignment=Alignment.Center) {
        Column(Modifier.widthIn(max=480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Image(painterResource(R.drawable.visidock_mark),null,Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(Color(0xFF071D49)))
            Text("VisiDock",style=MaterialTheme.typography.titleLarge)
            Text("Good meetings.\nNext chapters.",style=MaterialTheme.typography.headlineLarge)
            Text(if(register) "Keep the card. Pick up the conversation." else "Your people, ready when you are.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(register) OutlinedTextField(profileName,{profileName=it.take(100)},Modifier.fillMaxWidth(),label={Text("Your name")},singleLine=true,enabled=!busy)
            OutlinedTextField(email,{email=it.trim()},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Email address")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Password")},singleLine=true,visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={IconButton(onClick={visible=!visible}) {Icon(if(visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible) "Hide password" else "Show password")}})
            if(register) Text("Use at least 8 characters.",style=MaterialTheme.typography.bodySmall)
            DockButton(enabled=!busy && email.isNotBlank() && password.length>=if(register) 8 else 6,onClick={onSubmit(email,password,register,profileName)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(register) "Create account" else "Sign in")}
            if(!register) TextButton(enabled=!busy,onClick={onReset(email)}) {Text("Forgot password?")}
            TextButton(enabled=!busy,onClick={register=!register;password=""}) {Text(if(register) "Already have an account? Sign in" else "New here? Create an account")}
        }
    }
}

@Composable private fun SetupScreen() {
    EmptyState(Icons.Outlined.CloudOff,"Connect your workspace","This cloud build needs its Firebase Android configuration. Add app/google-services.json and rebuild, or install the separate demo build to explore VisiDock.",Modifier.fillMaxSize())
}





@Composable private fun ReadingStage(path:String,phase:String,onCancel:()->Unit) {
    BackHandler(onBack=onCancel)
    val bitmap by produceState<android.graphics.Bitmap?>(null,path) {value=withContext(Dispatchers.IO) {BitmapFactory.decodeFile(path)}}
    val enabled=android.animation.ValueAnimator.areAnimatorsEnabled()
    val position=if(enabled) {
        val transition=rememberInfiniteTransition(label="Reading card")
        val sweep by transition.animateFloat(0f,1f,infiniteRepeatable(tween(1900,easing=LinearEasing),RepeatMode.Reverse),label="Reading sweep")
        sweep
    } else 0f
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Text("From a card",style=MaterialTheme.typography.headlineLarge)
            Text("to a connection.",style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(32.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1.65f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)) {
                bitmap?.let {Image(it.asImageBitmap(),"Card being read",Modifier.fillMaxSize().padding(12.dp),contentScale=ContentScale.Fit)}
                if(enabled) Canvas(Modifier.fillMaxSize()) {val y=size.height*position;drawLine(Color(0xFFB8F36B).copy(alpha=.3f),Offset(0f,y),Offset(size.width,y),18.dp.toPx());drawLine(Color(0xFFB8F36B),Offset(0f,y),Offset(size.width,y),2.dp.toPx())}
            }
            Spacer(Modifier.height(28.dp));Text(phase,style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            Spacer(Modifier.height(8.dp));Text("The details stay on this device while we read.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp));TextButton(onClick=onCancel) {Text("Cancel reading")}
        }
    }
}
