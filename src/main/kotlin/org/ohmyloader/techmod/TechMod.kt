package org.ohmyloader.techmod

import org.ohmyloader.api.Mod
import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.OMLContentProvider
import org.ohmyloader.api.event.Events

/**
 * OML 打样科技模组（M1：材料链）。
 *
 * 规则（打样的目的所在）：
 * 1. 全程只用 oml-api —— 不碰 platform 逃生舱；
 * 2. 每遇到一个 API 缺口，记入 README-TECHMOD.md 的缺口清单，不在 mod 里绕过。
 *
 * M1 范围：铜/锡矿（方块）+ 掉落的生矿/锭/粉/板（物品）+ 粉碎/熔炼两条加工链。
 * 刻意超出当前 ContentRegistry 能力的部分（掉落、配方、世界生成）在声明处用 `Missing:` 注释标注，
 * 作为缺口清单的代码锚点。
 */
@Mod(id = "techmod", name = "Tech Mod", version = "0.1.0")
class TechMod : OMLModInitializer, OMLContentProvider {

    companion object {
        // M1 材料链声明：id 在这里集中列出，材料化产物在 onInitialize 后由句柄持有
        val ORE_BLOCKS = listOf("copper_ore", "tin_ore")
        val RAW_ITEMS = listOf("raw_copper", "raw_tin")
        val INGOT_ITEMS = listOf("copper_ingot", "tin_ingot")
        val DUST_ITEMS = listOf("copper_dust", "tin_dust")
        val PLATE_ITEMS = listOf("copper_plate", "tin_plate")
    }

    override fun declareContent(registry: ContentRegistry) {
        // 矿石：石头强度等级，需要镐才掉落
        for (ore in ORE_BLOCKS) {
            registry.declareBlock(ore) {
                destroyTime = 3.0f
                explosionResistance = 3.0f
                requiresCorrectToolForDrops = true
                // Missing: worldgen — 让矿出现在地下（矿石生成 API）
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
    }

    override fun onInitialize(context: ModContext) {
        println(
            "[techmod] init: id=${context.id} version=${context.version}, " +
                "${ORE_BLOCKS.size} ores + ${RAW_ITEMS.size + INGOT_ITEMS.size + DUST_ITEMS.size + PLATE_ITEMS.size} items declared"
        )

        Events.SERVER_TICK.register {
            // M2 将在这里驱动机器的 tick 逻辑（BlockEntity API 缺口落地后）
        }
    }
}
