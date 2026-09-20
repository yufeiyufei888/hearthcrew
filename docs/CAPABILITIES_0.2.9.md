# HearthCrew 0.2.9 能力矩阵

由 protocol/action-capabilities.json 生成，运行时模型说明及参数校验使用同一文件。存在能力不代表在所有地形、材料或第三方 Mod 下已验收。

| 动作 | 必需参数 | 允许参数 | 约束与当前行为 |
|---|---|---|---|
| MOVE | position | position, count | 默认陆路，不主动进入水域；目的地须已观察。 不传count（仅兼容旧count=0）。 |
| FOLLOW | target | target, count | 使用真实世界目标；接受不等于完成。 不传count（仅兼容旧count=0）。 |
| MINE | position | position, count, resource | position为起点，count默认1最多64块；resource可限定起始方块种类。连续采掘同类连通自然方块，起点附近8格、身体起点32格内；不能挖脚下支撑或隐式开挖其他材料。回执分别列出requested、broken、acquired及pendingDrops；已破坏但未拾取时恢复回收，不重挖空气。 自动从整个背包选适用工具，无需先SELECT；数量任务优先提交所需批次而非逐块调用。缺工具先查真实配方和自有材料，准备工作仍属当前阶段。 |
| EXCAVATE | position | position, count | 32格内安全两格高通路，破坏预算默认32上限64；不采矿、不搭桥。 |
| PLACE | position | position, resource, count | 使用真实世界目标；接受不等于完成。 不传count（仅兼容旧count=0）。 |
| EAT | 无 | count | 使用真实世界目标；接受不等于完成。 不传count（仅兼容旧count=0）。 |
| SLEEP | position | position, count | 使用真实世界目标；接受不等于完成。 不传count（仅兼容旧count=0）。 |
| ATTACK | target | target, count | 使用真实世界目标；接受不等于完成。 不传count（仅兼容旧count=0）。 |
| GUARD | target | target, count | 使用真实世界目标；接受不等于完成。 不传count（仅兼容旧count=0）。 |
| PICKUP | target | target, count | 使用真实世界目标；接受不等于完成。 不传count（仅兼容旧count=0）。 |
| PORTAL | position | position, count | 使用真实世界目标；接受不等于完成。 不传count（仅兼容旧count=0）。 |
| WAIT | 无 | count | 使用真实世界目标；接受不等于完成。 |
| CRAFT | resource | resource, position, count | resource为服务器配方ID，count制作次数，超过2x2需position工作台。 |
| TRANSFER | target, resource | target, resource, count | 使用真实世界目标；接受不等于完成。 |
| GATHER | resource, count | resource, count | 主世界八类原木；每段检验天然树木及保护，附近原木未经采木验证。 |
| SELECT | 无 | slot, count | slot必填0..35；兼容count，冲突拒绝。 |
| BUILD | steps | steps, count | 有序1..16步，设施需正确支撑/方向/多格占用；整段结束才汇报。 不传count（仅兼容旧count=0）。 |
| USE_ITEM | 无 | resource, count | 持续使用主手物品，count为最长游戏tick；只有实际效果可验证才完成。 |
| INTERACT | 无 | position, target, resource, count | position或target恰好一项。原版右键，支持耕地播种/成熟作物右键、门/设施、剪羊毛/繁殖；成熟作物收获可MINE后补种。 不传count（仅兼容旧count=0）。 |
| EQUIP | resource | resource, count | 真实物品自动装入对应防具/副手或主手槽。 不传count（仅兼容旧count=0）。 |
| STORE | position, resource, count | position, resource, count | 公共箱/木桶/炉子存入，真实槽位与数量。 |
| TAKE | position, resource, count | position, resource, count | 公共容器取出，遵守槽位与订单归属。 |
| PROCESS | position, resource, count | position, resource, count | 真实炉子投料与燃料订单；仅完成投料，之后observe并COLLECT_PROCESS，不能声称已产出。 |
| COLLECT_PROCESS | position | position, count | 收取本人订单的实际产物；未完成加工返回等待条件。 不传count（仅兼容旧count=0）。 |
| SWIM | position | position, count | Luna明确选择游泳；持续水面推进，目标必须安全可达。 不传count（仅兼容旧count=0）。 |
| EXPLORE | position | position, count | Luna选择方向，分段推进最多256格；未知和无路返回条件。 不传count（仅兼容旧count=0）。 |
| LAUNCH_BOAT | position | position, resource, count | 背包真实船只放入已观察水面，返回实际boat UUID。 不传count（仅兼容旧count=0）。 |
| BOARD_BOAT | target | target, count | target船UUID，必须空闲驾驶位且为伙伴船。 不传count（仅兼容旧count=0）。 |
| SAIL | position | position, count | 已上船后用原版推进到水面坐标；受阻换航点。 不传count（仅兼容旧count=0）。 |
| DISEMBARK | position | position, count | position为近处安全陆地；使用原版安全下船位置。 不传count（仅兼容旧count=0）。 |
| COLLECT_RESOURCE | resource, count, candidates | resource, count, candidates, position, radius | 目标物品resource、新增数量count 1..64、候选自然方块candidates 1..16；position为范围中心，radius 1..32默认32。本动作限矿物/石材候选；原木必须使用GATHER完成自然树验证。仅采这些方块，不隐式开路。缺通路时另行明确EXCAVATE预算。实际原版拾取事件累计，非背包总量。 |
| SEQUENCE | actions | actions | actions包含1..8个{kind,parameters}已确定的准备步骤，不嵌套SEQUENCE，不含长期FOLLOW/GUARD或安全反射。逐步核验，失败即停，已完成步骤不重放。 |
| YIELD | 无 | 无 | 主动让路：执行器选择附近2..4格安全站位，带空间租约。无位置或动态阻挡则有限等待后说明原因；不传坐标，不改地形。 |

## 共通边界

- 真实物品、原版交互、碰撞、消耗和实际回执；接受不是完成。
- SEQUENCE 最多八步，失败即停；原木走 GATHER，COLLECT_RESOURCE 不绕过自然树保护。
- observe 每回合环境扫描两次、不同定向查询四次；缓存命中和非法参数不扣额度。
- 交流回合只观察、记忆、发消息、处理协作提案；身体动作必须进入正常执行流程。
- 世界退出后不自动重放旧世界修改；完整验收范围见 [修复报告](RELEASE_0.2.9.md)。
