package org.ohmyloader.techmod

import org.ohmyloader.api.Mod
import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.command.OMLArgumentType
import org.ohmyloader.api.command.OMLCommandProvider
import org.ohmyloader.api.command.OMLCommandRegistry
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.OMLContentProvider

/**
 * OML 打样科技模组（M1：材料链）。
 *
 * 规则（打样的目的所在）：
 * 1. 全程只用 oml-api —— 不碰 platform 逃生舱；
 * 2. 每遇到一个 API 缺口，记入 README-TECHMOD.md 的缺口清单，不在 mod 里绕过。
 *
 * M2 范围：在 M1 材料链之上加入生成（矿石自然生成）与机器（压制机：BlockEntity tick +
 * 持久进度）——M2 之前卡死的两条加工链现已可声明。
 * 刻意超出当前 ContentRegistry 能力的部分（掉落、配方、世界生成）在声明处用 `Missing:` 注释标注，
 * 作为缺口清单的代码锚点。
 */
@Mod(
    id = "techmod",
    name = "Tech Mod",
    version = "0.1.0",
    dependencies = ["oml_testmod@>=0.1.0"],
)
class TechMod : OMLModInitializer, OMLContentProvider, OMLCommandProvider {

    companion object {
        // M1 材料链声明：id 在这里集中列出，材料化产物在 onInitialize 后由句柄持有
        val ORE_BLOCKS = listOf("copper_ore", "tin_ore")
        val RAW_ITEMS = listOf("raw_copper", "raw_tin")
        val INGOT_ITEMS = listOf("copper_ingot", "tin_ingot")
        val DUST_ITEMS = listOf("copper_dust", "tin_dust")
        val PLATE_ITEMS = listOf("copper_plate", "tin_plate")

        /** One press cycle, in game ticks (3 seconds). */
        const val PRESS_TICKS_PER_CYCLE = 60
    }

    override fun declareContent(registry: ContentRegistry) {
        // 矿石：石头强度等级，需要镐才掉落
        for (ore in ORE_BLOCKS) {
            registry.declareBlock(ore) {
                destroyTime = 3.0f
                explosionResistance = 3.0f
                requiresCorrectToolForDrops = true
                generateAsOre {
                    veinSize = 8
                    perChunk = 6
                    minY = 16
                    maxY = 64
                }
            }
        }
        // 挖掘矿石掉落对应原矿（掉落表覆盖，数据包 JSON 经注入器应答）
        registry.declareBlockDrop("copper_ore", "raw_copper")
        registry.declareBlockDrop("tin_ore", "raw_tin")

        // 原矿：挖掘矿石的产物
        for (raw in RAW_ITEMS) {
            registry.declareItem(raw)
        }

        // 锭：原矿熔炼产物（熔炼配方，数据包 JSON 经注入器应答）
        for ([raw, ingot] in RAW_ITEMS.zip(INGOT_ITEMS)) {
            registry.declareItem(ingot)
            registry.declareSmelting(input = raw, result = ingot, experience = 0.7)
        }

        // 粉：原矿粉碎产物（Missing: 手动配方/磨粉机；M2 机器产出）
        for (dust in DUST_ITEMS) {
            registry.declareItem(dust)
        }

        // 板：锭压制产物（Missing: 压制配方；M2 机器产出）
        for (plate in PLATE_ITEMS) {
            registry.declareItem(plate)
        }

        // 压制机：M2 BlockEntity API 的验收件——tick 绑定在方块上（不再挂全局 SERVER_TICK），
        // 进度存 OMLBlockData 随存档持久化；板块的产出途径仍是缺口（需要背包 API，见缺口清单）
        registry.declareBlock("press") {
            destroyTime = 3.5f
            explosionResistance = 6.0f
            requiresCorrectToolForDrops = true
            blockEntity {
                tick { event ->
                    val progress = event.data.getInt("progress") + 1
                    if (progress >= PRESS_TICKS_PER_CYCLE) {
                        event.data.putInt("progress", 0)
                        println("[techmod] press at ${event.x}/${event.y}/${event.z}: cycle complete")
                    } else {
                        event.data.putInt("progress", progress)
                    }
                }
            }
        }
        registry.declareShapedCrafting(
            result = "press",
            pattern = listOf("III", "I I", "III"),
            key = mapOf('I' to "minecraft:iron_ingot"),
        )
    }

    override fun declareCommands(commands: OMLCommandRegistry) {
        // M3 / T-3.1：命令注册 API 的打样——根命令 + 类型化参数两种形态
        commands.register("techmod_ping") {
            executes { source ->
                val prefix = context.config.getString("ping_prefix")
                println("[techmod] $prefix (executed by ${source.name})")
            }
            argument("loud", OMLArgumentType.BOOLEAN) {
                executes { source ->
                    val prefix = context.config.getString("ping_prefix")
                    val loud = source.getBoolean("loud") || context.config.getBoolean("loud_by_default")
                    val message = if (loud) prefix.uppercase() + "!!" else prefix
                    println("[techmod] $message (executed by ${source.name})")
                }
            }
        }
    }

    private lateinit var context: ModContext

    override fun onInitialize(context: ModContext) {
        this.context = context
        // M3 / T-3.2：配置 API 的打样——声明条目（首次读取时生成 config/techmod.toml），命令读取
        context.config.define("ping_prefix", "pong", "Prefix of the /techmod_ping reply")
        context.config.define("loud_by_default", false, "Whether /techmod_ping shouts by default")
        println(
            "[techmod] init: id=${context.id} version=${context.version}, " +
                "${ORE_BLOCKS.size} ores + ${RAW_ITEMS.size + INGOT_ITEMS.size + DUST_ITEMS.size + PLATE_ITEMS.size} items declared",
        )

        println(
            "[techmod] machine: press block entity declared — tick is bound to the block, " +
                "one cycle = $PRESS_TICKS_PER_CYCLE server ticks",
        )
    }
}
