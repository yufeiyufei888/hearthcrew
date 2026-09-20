# P0 Gameplay 适配器契约

本文件描述 `gameplay` 包向根实体与生命周期代码提供的窄接口。所有调用必须发生在 Minecraft 服务端线程；适配器不创建新的 AI 世界写入者。

## 敌对目标

`CompanionTargeting.canAcquire(Mob attacker, LivingEntity companion, BiPredicate<Mob, LivingEntity> speciesRule)` 保留 NeoForge/Minecraft 战斗目标的通用约束：

- 攻击者必须是 `Enemy`，且双方存活、可攻击、不是同盟；
- 显式拒绝和平难度，并使用 `TargetingConditions.forCombat()`，包括可见性、视线、跟随距离和 `canAttackType`；
- `speciesRule` 用于物种特有条件，例如末影人注视、溺尸附加筛选或苦力怕的特殊目标逻辑。

`findNearest(Mob, List<? extends LivingEntity>, speciesRule)` 只在当前目标为空、死亡、移除或已不可攻击时返回同伴；当前仍有效的原版目标不会被替换。返回结果不直接写入 Mob 目标，根调用方负责在服务端线程中通过既有目标变更路径提交。

该适配器不能声称“所有敌对生物自动等同玩家”。原版许多目标选择器硬编码 `Player.class`；根层必须为每个接入的物种提供 `speciesRule`，并为未接入物种保持明确的 `unsupported` 状态。不能通过给同伴伪造 Player 类型来解决。

## 玩家式重生

`CompanionRespawn.resolve(MinecraftServer, EntityType<?>, Request)` 是只读选择：

1. 使用已保存维度和出生方块；
2. 在可工作的维度使用床，并用 `BedBlock.findStandUpPosition` 验证实体尺寸和安全站位；
3. 在可工作的维度使用有余量的重生锚，并用 `RespawnAnchorBlock.findStandUpPosition` 验证站位；
4. 出生维度缺失、方块无效、床所在维度不可睡或锚点无充能时回退到主世界安全出生点；
5. 没有安全主世界点时返回空值，生命周期代码必须停在待处理状态，不能传送脱困。

`Choice.consumesAnchor()` 表示这次死亡代际是否应消耗锚点；根生命周期在确认只生成一个新身体后调用 `consumeAnchor(Choice)` 一次。选择结果同时记录选定时的充能值，重复调用或选择已过期时会失败，不改变世界；只有仍是同一重生锚且充能大于零的方块才会扣一格。

床/锚点选择只解决安全出生位置，不实现死亡掉落。掉落、经验、消失诅咒、`keepInventory` 和死亡代际幂等由根实体/生命周期负责；不可把 `doMobLoot` 作为同伴背包掉落开关。

## 末影龙伤害契约

1.21.1 `EnderDragon.hurt` 对普通自定义 Mob 的 `mobAttack` 不扣血。根层必须注册原创伤害类型（建议 `hearthcrew:companion_attack`），并将其加入 `minecraft:always_hurts_ender_dragons` 对应的伤害类型标签，同时保持伤害源实体为实际同伴。

该标签只解决末影龙的伤害门槛，不授予创造、无敌、超距离或无冷却能力。末影水晶的玩家归属、龙战进度和原版 Player 统计仍需单独记录；未完成适配前，龙战能力必须报告为 `unsupported`。

## 证据边界

GameTest 可验证目标选择、重生位置、锚点扣费和无重复实体；真实客户端仍需验证正常敌怪仇恨、死亡掉落、下界/末地往返、区块 ticket 上限和末影龙通关。静态可调用不等于已经实测。
