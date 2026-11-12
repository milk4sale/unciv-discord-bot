package civbot

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Owns the Unciv engine. Unciv keeps global state (UncivGame.Current, caches), so
 * every engine call goes through ONE thread. Discord handlers call [run] and get
 * the result back; games never touch each other mid-operation.
 */
object Engine {
    private val log = LoggerFactory.getLogger("Engine")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "unciv-engine").apply { isDaemon = true } }

    /** Root of the Unciv checkout (contains android/assets and android/Images.*) */
    lateinit var uncivDir: File
        private set

    fun init(uncivDir: File) {
        this.uncivDir = uncivDir
        run {
            // Unciv reads its rulesets with relative "internal" paths, so the JVM must be
            // started from android/assets (run.sh does this). Fail loudly otherwise.
            require(File("jsons").isDirectory) {
                "Start the bot from <unciv>/android/assets (use run.sh). Current dir: ${File(".").absolutePath}"
            }
            Gdx.files = HeadlessFiles()
            val game = UncivGame(true)
            UncivGame.Current = game
            game.settings = GameSettings().apply {
                showTutorials = false
                turnsBetweenAutosaves = 1_000_000
                // Standing orders (multi-turn Go to, Automate, Explore, Connect road) carry on at the start of each
                // human turn. The desktop app does this from its world screen, which the bot doesn't have.
                automatedUnitsMoveOnTurnStart = true
            }
            game.files = UncivFiles(Gdx.files)
            RulesetCache.loadRulesets(noMods = true)
            log.info("Unciv engine ready ({} rulesets)", RulesetCache.size)
        }
    }

    /** Run [block] on the engine thread and wait for its result (exceptions are re-thrown). */
    fun <T> run(block: () -> T): T {
        val future = exec.submit(Callable { block() })
        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Run [block] with [game] as Unciv's "current" game (some engine code peeks at it). */
    fun <T> withGame(game: GameInfo, block: (GameInfo) -> T): T = run {
        UncivGame.Current.gameInfo = game
        block(game)
    }

    fun serialize(game: GameInfo): String = UncivFiles.gameInfoToString(game, forceZip = true)
    fun deserialize(data: String): GameInfo = UncivFiles.gameInfoFromString(data)

    /**
     * Call an engine function Unciv doesn't expose publicly (private/internal AI helpers
     * we reuse as the "Advisor"). Returns false instead of throwing if the engine changed.
     */
    /** Like [callHidden] but returns the function's result (null if it couldn't be called). */
    fun callHiddenResult(target: Any, name: String, vararg args: Any): Any? {
        val method = target.javaClass.declaredMethods.firstOrNull {
            (it.name == name || it.name.startsWith("$name$")) && it.parameterCount == args.size
        } ?: return null
        return try { method.isAccessible = true; method.invoke(target, *args) } catch (e: Exception) {
            log.warn("Engine helper {}.{} failed: {}", target.javaClass.simpleName, name, (e.cause ?: e).toString()); null
        }
    }

    fun callHidden(target: Any, name: String, vararg args: Any): Boolean {
        val method = target.javaClass.declaredMethods.firstOrNull {
            (it.name == name || it.name.startsWith("$name$")) && it.parameterCount == args.size
        }
        if (method == null) {
            log.warn("Engine helper {}.{} not found - skipped", target.javaClass.simpleName, name)
            return false
        }
        return try {
            method.isAccessible = true
            method.invoke(target, *args)
            true
        } catch (e: Exception) {
            log.warn("Engine helper {}.{} failed: {}", target.javaClass.simpleName, name, (e.cause ?: e).toString())
            false
        }
    }
}
