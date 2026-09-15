package moe.nea.firmament.repo

import io.github.moulberry.repo.NEURepository
import io.github.moulberry.repo.NEURepositoryException
import io.github.moulberry.repo.data.NEUItem
import io.github.moulberry.repo.data.NEURecipe
import io.github.moulberry.repo.data.Rarity
import java.nio.file.Path
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket
import net.minecraft.world.item.crafting.SelectableRecipe
import net.minecraft.util.StringRepresentable
import moe.nea.firmament.Firmament
import moe.nea.firmament.Firmament.logger
import moe.nea.firmament.events.ReloadRegistrationEvent
import moe.nea.firmament.util.ErrorUtil
import moe.nea.firmament.util.MC
import moe.nea.firmament.util.MinecraftDispatcher
import moe.nea.firmament.util.SkyblockId
import moe.nea.firmament.util.TestUtil
import moe.nea.firmament.util.data.Config
import moe.nea.firmament.util.data.ManagedConfig
import moe.nea.firmament.util.tr

object RepoManager {
	@Config
	object TConfig : ManagedConfig("repo", Category.META) {
		var username by string("username") { "NotEnoughUpdates" }
		var reponame by string("reponame") { "NotEnoughUpdates-REPO" }
		var branch by string("branch") { "master" }
		val autoUpdate by toggle("autoUpdate") { true }
		val reset by button("reset") {
			username = "NotEnoughUpdates"
			reponame = "NotEnoughUpdates-REPO"
			branch = "master"
			markDirty()
		}
		val enableREI by toggle("enable-rei") { true }
		val disableItemGroups by toggle("disable-item-groups") { true }
		val reload by button("reload") {
			markDirty()
			Firmament.coroutineScope.launch {
				RepoManager.reload()
			}
		}
		val redownload by button("redownload") {
			markDirty()
			RepoManager.launchAsyncUpdate(true)
		}
		val alwaysSuperCraft by toggle("enable-super-craft") { true }
		var warnForMissingItemListMod by toggle("warn-for-missing-item-list-mod") { true }
		val perfectRenders by choice("perfect-renders") { PerfectRender.RENDER }
	}

	enum class PerfectRender(val label: String) : StringRepresentable {
		NOTHING("nothing"),
		RENDER("render"),
		RENDER_AND_TEXT("text"),
		;

		fun rendersPerfectText() = this == RENDER_AND_TEXT
		fun rendersPerfectVisuals() = this == RENDER || this == RENDER_AND_TEXT

		override fun getSerializedName(): String = label
	}

	val currentDownloadedSha by RepoDownloadManager::latestSavedVersionHash

	private const val REI_RELOAD_DEBOUNCE_TICKS = 40
	private const val REI_RELOAD_RETRY_TICKS = 20
	var recentlyFailedToUpdateItemList = false
	private var reiReloadDelayTicks = 0

	private fun scheduleDeferredReiReload() {
		recentlyFailedToUpdateItemList = true
		reiReloadDelayTicks = REI_RELOAD_DEBOUNCE_TICKS
	}

	val essenceRecipeProvider = EssenceRecipeProvider()
	val recipeCache = BetterRepoRecipeCache(essenceRecipeProvider, ReforgeStore)
	val miningData = MiningRepoData()
	val overlayData = ModernOverlaysData()
	val enchantedBookCache = EnchantedBookCache()
	val enchantData = EnchantData()

	fun makeNEURepository(path: Path): NEURepository {
		return NEURepository.of(path).apply {
			registerReloadListener(overlayData)
			registerReloadListener(ItemCache)
			registerReloadListener(RepoItemTypeCache)
			registerReloadListener(ExpLadders)
			registerReloadListener(ItemNameLookup)
			registerReloadListener(ReforgeStore)
			registerReloadListener(essenceRecipeProvider)
			registerReloadListener(recipeCache)
			registerReloadListener(miningData)
			registerReloadListener(enchantedBookCache)
			registerReloadListener(enchantData)
			ReloadRegistrationEvent.publish(ReloadRegistrationEvent(this))
			registerReloadListener {
				if (TestUtil.isInTest) return@registerReloadListener
				Firmament.coroutineScope.launch(MinecraftDispatcher) {
					// REI 26.2 may still be initializing its registry provider while a world is
					// being joined. Triggering its synthetic recipe refresh immediately can race
					// the server's tag/recipe packets. Coalesce repo reloads and wait for a
					// short period of stable level ticks before asking REI to refresh.
					scheduleDeferredReiReload()
				}
			}
		}
	}

	lateinit var neuRepo: NEURepository
		private set

	fun getAllRecipes() = neuRepo.items.items.values.asSequence().flatMap { it.recipes }

	fun getRecipesFor(skyblockId: SkyblockId): Set<NEURecipe> = recipeCache.recipes[skyblockId] ?: setOf()
	fun getUsagesFor(skyblockId: SkyblockId): Set<NEURecipe> = recipeCache.usages[skyblockId] ?: setOf()

	private fun trySendClientboundUpdateRecipesPacket(): Boolean {
		val minecraft = Minecraft.getInstance()
		val connection = minecraft.connection ?: return false
		if (minecraft.level == null || minecraft.gameMode == null) return false
		return connection.handleUpdateRecipes(
			ClientboundUpdateRecipesPacket(mutableMapOf(), SelectableRecipe.SingleInputSet.empty())
		) != null
	}

	init {
		ClientTickEvents.START_LEVEL_TICK.register(ClientTickEvents.StartLevelTick {
			if (recentlyFailedToUpdateItemList) {
				if (reiReloadDelayTicks > 0) {
					reiReloadDelayTicks--
				} else if (trySendClientboundUpdateRecipesPacket()) {
					recentlyFailedToUpdateItemList = false
				} else {
					// The connection/registries are not ready yet. Retry at a bounded cadence
					// rather than hammering REI on every client tick.
					reiReloadDelayTicks = REI_RELOAD_RETRY_TICKS
				}
			}
		})
	}

	fun getNEUItem(skyblockId: SkyblockId): NEUItem? = neuRepo.items.getItemBySkyblockId(skyblockId.neuItem)

	fun downloadOverridenBranch(branch: String) {
		Firmament.coroutineScope.launch {
			RepoDownloadManager.downloadUpdate(true, branch)
			reload()
		}
	}

	fun launchAsyncUpdate(force: Boolean = false) {
		Firmament.coroutineScope.launch {
			RepoDownloadManager.downloadUpdate(force)
			reload()
		}
	}

	fun reloadForTest(from: Path) {
		neuRepo = makeNEURepository(from)
		reloadSync()
	}


	suspend fun reload() {
		withContext(Dispatchers.IO) {
			reloadSync()
		}
	}

	fun reloadSync() {
		try {
			logger.info("Repo reload started.")
			neuRepo.reload()
			logger.info("Repo reload completed.")
		} catch (exc: NEURepositoryException) {
			ErrorUtil.softError("Failed to reload repository", exc)
			MC.sendChat(
				tr(
					"firmament.repo.reloadfail",
					"Failed to reload repository. This will result in some mod features not working."
				)
			)
		}
	}

	private var wasInitialized = false
	fun initialize() {
		if (wasInitialized) return
		wasInitialized = true
		System.getProperty("firmament.testrepo")?.let { compTimeRepo ->
			reloadForTest(Path.of(compTimeRepo))
			return
		}
		neuRepo = makeNEURepository(RepoDownloadManager.repoSavedLocation)
		if (TConfig.autoUpdate) {
			launchAsyncUpdate()
		} else {
			Firmament.coroutineScope.launch {
				reload()
			}
		}
	}

	init {
		if (TestUtil.isInTest) {
			initialize()
		}
	}

	fun getPotentialStubPetData(skyblockId: SkyblockId): PetData? {
		val parts = skyblockId.neuItem.split(";")
		if (parts.size != 2) {
			return null
		}
		val (petId, rarityIndex) = parts
		if (!rarityIndex.all { it.isDigit() }) {
			return null
		}
		val intIndex = rarityIndex.toInt()
		if (intIndex !in Rarity.entries.indices) return null
		if (petId !in neuRepo.constants.petNumbers) return null
		return PetData(Rarity.entries[intIndex], petId, 0.0, true)
	}

	fun getRepoRef(): String {
		return "${TConfig.username}/${TConfig.reponame}#${TConfig.branch}"
	}

	fun shouldLoadREI(): Boolean = TConfig.enableREI
}
