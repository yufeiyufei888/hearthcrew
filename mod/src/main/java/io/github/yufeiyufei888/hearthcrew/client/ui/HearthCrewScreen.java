package io.github.yufeiyufei888.hearthcrew.client.ui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Compact, non-pausing, Chinese HearthCrew in-game dashboard. */
public final class HearthCrewScreen extends Screen {
    private enum Page { TEAM, TASKS, CHAT, INVENTORY, SETTINGS, DIAGNOSTICS, GUIDE }

    private static final String CODEX_PROMPT = "请先检查本机 HearthCrew 是否已安装并运行现有 scripts\\start-play-controller.ps1；如果控制器未运行，按项目已有脚本启动，使用官方 Codex CLI 登录流程完成登录，然后从 PCL 进入 Minecraft。不要另启第二个控制器，不要安装第二套 MCP，不要修改 Codex 桌面端全局配置，也不要索取或输出登录凭据。连接建立后，请通过当前已配对的 HearthCrew 游戏连接调用 status，确认游戏、控制器、App Server 和三名伙伴的实际状态；默认让三名独立 Luna 伙伴自主推进生存，由各自模型决定分工和下一步行动，只在影响队友下一步、真实求助、回答玩家或总任务交付时简短交流；基础准备安静完成，按需只读查询队友背包，逐块进度留在诊断，不用一个模型代替三人决策。游戏内可在 T 聊天栏点名 @Ember 或 @小队；提问先对话，明确任务才改派，H 对话页保留历史。请在诊断页核对三个不同会话 ID；使用 command 下达中文生存任务，使用 events 和 status 根据真实位置、背包、方块变化、动作回执和伙伴公开回复判断结果，需要暂停、恢复、切换自主模式、待命或急停时使用 control。配置目标为 gpt-5.6-luna + high，fast/服务层以游戏内诊断页实际显示为准。若连接不存在，请说明缺少哪一步并等待我处理，不要猜测或伪造完成状态。";
    private static final List<String> GUIDE_TEXT = List.of(
            "入门：让炉火伙伴开始工作",
            "任务看板：先看三人的目标、阶段与真实当前步骤，点击伙伴卡片查看详情和阶段历史。对话页只显示真实发言，可筛选伙伴互聊或与你交流；向上阅读不会自动跳回，点击“最新”返回底部。“更早消息”只读历史，不会唤醒模型。",
            "0.3.2 开发版（兼容0.3.0存档），尚未完成真实陪玩验收：伙伴按整段任务推进，逐块结果不必聊天；阶段完成本身无需发言；只有协作依赖变化、真实求助、回答玩家或总任务交付时简短汇报。没说话不代表没有工作。重生先核对新背包和上次任务。地下矿石没有现成通路时可评估安全开路；受保护区域、液体、支撑不足或预算不足会明确停止，不能传送脱困。",
            "普通请求等当前任务阶段结束再接；明确说“立即改派”可以中断。伙伴使用原版 ServerPlayer 身体，支持玩家名字命令。",
            "1. 先启动项目已有的 scripts\\start-play-controller.ps1，并按官方 Codex CLI 登录流程完成登录；停止脚本是 scripts\\stop-play-controller.ps1。然后从 PCL 启动 Minecraft，进入世界后 Mod 会自动连接本机控制器。不要在同一运行时另启第二个控制器。",
            "聊天：T 栏输入 @Ember 你在做什么 或 @小队 帮我采木。点名才会交给伙伴；聊天同时保存在 H 对话页，设置页可隐藏原版聊天。",
            "2. 按 H 打开面板，在“诊断”页确认游戏、控制器、App Server 和三名角色的实际状态；模型和 fast 服务层以这里显示的实际生效配置为准。",
            "3. 在“对话”页输入中文任务。点击输入框左侧选择小队或指定伙伴，发送后显示真实收件人；伙伴的公开回复、协商会按 Ember、Moss、Flint 显示；详细动作回执在诊断页查看。",
            "新世界会初始化三名空背包伙伴。可以先不下指令，观察各自 Luna 如何选择生存阶段；没有附近资源时应评估探索，跨水域需选择绕行、游泳或造船。",
            "持续任务：例如“采集16个煤炭，缺工具先准备”。Luna可选择按新增物品数量连续采集，或一次安排最多8步已确定的准备动作；真实缺料、危险或路径失败时会停止并重新决策。原木使用自然树采集，不能借采矿拆建筑。",
            "资源共享：三人可按当前任务获取队友资源摘要，或按需只读查看队友槽位与装备。未展开、未同步和确认空背包分别显示；持有、预留和交付同意是不同信息。普通库存变化不会唤醒全队。",
            "协作让路：伙伴先提出并接受请求，再在当前动作结束后实际移动；聊天答应不代表已经执行。长时间无动作时查看诊断的思考耗时、等待条件或版本不兼容提示。",
            "Codex 桌面端接入提示词（点击页面下方按钮复制）：",
            CODEX_PROMPT,
            "三名伙伴先自主选择并完成自己的工作；普通请求加入待办，明确说“立即执行/改派”才中断。队友忙碌时先协商，不能强派任务。",
            "遇到敌人时本地自卫不等待模型；低血量、被围攻或苦力怕引爆时优先撤退。待命保留自卫，暂停与急停会停止它。",
            "观察 recipes 查询真实配方，containers 查询公共设施与加工订单；PROCESS 投料后可继续其他工作，COLLECT_PROCESS 收取实际产物。",
            "/hearthcrew public x y z 将近处容器标记为公共；未标记的玩家箱子保持私人。",
            "游戏内斜杠命令（备用控制）",
            "/hearthcrew locate Ember  查看伙伴维度、整数坐标和实体 UUID（支持名字补全）",
            "/hearthcrew tp Ember      将自己传送到伙伴附近，支持跨维度；需作弊/管理员权限",
            "原版示例：/tp Ember、/tp Ember ~ ~ ~、/kill Moss、/give Flint minecraft:iron_pickaxe",
            "原版命令遵守原权限；需启用作弊才能使用传送、给予等命令。手动传送不属于正常生存验收。",
            "顶部“加入伙伴”可一键补齐三人，重复点击不会重复创建；离线也可创建身体。",
            "/hearthcrew              查看帮助与当前伙伴状态",
            "/hearthcrew join Ember   创建 Ember；每次只创建一名并站到空旷处再创建下一名",
            "/hearthcrew join Moss    创建 Moss",
            "/hearthcrew join Flint   创建 Flint",
            "/hearthcrew status       查看伙伴生命、饥饿和当前动作",
            "/hearthcrew follow       让第一个伙伴跟随玩家",
            "/hearthcrew move x y z   让第一个伙伴移动到已加载坐标",
            "/hearthcrew mine x y z   让第一个伙伴采掘已加载坐标",
            "/hearthcrew stop         停止全部伙伴动作",
            "/hearthcrew resume       恢复全部伙伴动作",
            "/hearthcrew autonomy on  开启三名伙伴的 Luna 自主游玩",
            "/hearthcrew autonomy off 关闭自主游玩（不取消玩家已下达任务）",
            "/hearthcrew standby      让普通工作结束并进入待命",
            "斜杠命令是游戏内备用入口；需要三人协作、记忆和真实回报时，请优先使用对话页。"
    );

    private Page page = Page.TASKS;
    private String taskBot="";
    private int chatFilter;
    private boolean chatFollow=true,newChat;
    private String chatFirst="";
    private int chatMeasuredHeight, teamContentHeight;
    private int taskContentHeight, diagnosticContentHeight;
    private final java.util.List<TaskHit> taskHits=new java.util.ArrayList<>();
    private record TaskHit(int x,int y,int width,int height,Runnable click){}
    private int selectedCompanion;
    private String commandRecipient="";
    private int scrollOffset;
    private int contentTop;
    private int contentBottom;
    private int contentLeft;
    private int contentRight;
    private EditBox commandBox;
    private Button pauseButton;
    private ItemStack hoveredStack = ItemStack.EMPTY;
    private String lastChatTail = "";

    private record InventoryLayout(boolean horizontalSelectors, int selectorWidth, int selectorY,
                                   int titleY, int labelY, int mainY, int hotbarLabelY, int hotbarY,
                                   int bottomY, int gridX, int equipmentX, int slotSize, int slotStep) {}

    public HearthCrewScreen() {
        super(Component.literal("炉火伙伴"));
    }

    @Override
    protected void init() {
        String draft = commandBox == null ? "" : commandBox.getValue();
        clearWidgets();
        clearFocus();
        contentTop = page==Page.CHAT||page==Page.TASKS?76:52;
        contentBottom = Math.max(contentTop + 80, height - (page == Page.GUIDE || page == Page.SETTINGS ? 62 : 34));
        contentLeft = 6;
        contentRight = Math.max(contentLeft + 100, width - 6);
        int tabY = 28;
        int tabGap = 2;
        int tabWidth = Math.max(42, (width - 12 - tabGap * (Page.values().length - 1)) / Page.values().length);
        Page[] pages = Page.values();
        for (int index = 0; index < pages.length; index++) {
            Page target = pages[index];
            addRenderableWidget(Button.builder(Component.literal(pageName(target)), button -> selectPage(target))
                    .bounds(6 + index * (tabWidth + tabGap), tabY, tabWidth, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> ClientUi.requestStatus(true))
                .bounds(Math.max(6, width - 50), 3, 44, 18).build());
        addRenderableWidget(Button.builder(Component.literal("启动控制器"), button -> ControllerLauncher.start()).bounds(Math.max(6,width-145),3,90,18).build());
        addRenderableWidget(Button.builder(Component.literal("加入伙伴"), button -> ClientUi.joinCompanions()).bounds(Math.max(6,width-223),3,74,18).build());
        if (page == Page.GUIDE) {
            addRenderableWidget(Button.builder(Component.literal("复制接入提示词"), button -> copyCodexPrompt())
                    .bounds(6, height - 57, 120, 20).build());
        }
        if (page == Page.SETTINGS) {
            int settingsButtonY = height - 57;
            int settingsButtonWidth = Math.max(48, Math.min(74, (contentRight - contentLeft - 16) / 4));
            int settingsX = Math.max(contentLeft + 4, contentRight - settingsButtonWidth * 4 - 10);
            addRenderableWidget(Button.builder(Component.literal("自主开启"), button -> ClientUi.sendControl("auto_on"))
                    .bounds(settingsX, settingsButtonY, settingsButtonWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("自主关闭"), button -> ClientUi.sendControl("auto_off"))
                    .bounds(settingsX + settingsButtonWidth + 2, settingsButtonY, settingsButtonWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal(PublicChat.enabled() ? "聊天：开" : "聊天：关"), button -> {
                PublicChat.toggle(); button.setMessage(Component.literal(PublicChat.enabled() ? "聊天：开" : "聊天：关"));
            }).bounds(settingsX + (settingsButtonWidth + 2) * 3, settingsButtonY, settingsButtonWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("全队待命"), button -> ClientUi.sendControl("standby"))
                    .bounds(settingsX + (settingsButtonWidth + 2) * 2, settingsButtonY, settingsButtonWidth, 20).build());
        }

        if(page==Page.TASKS){
            addRenderableWidget(Button.builder(Component.literal("重新评估目标"),b->ClientUi.retryPlanning(taskBot)).bounds(202,52,88,20).build());
            addRenderableWidget(Button.builder(Component.literal("三人总览"),b->{taskBot="";scrollOffset=0;}).bounds(8,52,76,20).build());
            addRenderableWidget(Button.builder(Component.literal("读取阶段历史"),b->{if(!taskBot.isBlank())ClientUi.requestStages(taskBot,true);}).bounds(88,52,110,20).build());
        }
        if(page==Page.CHAT){
            String[] filters={"全部","伙伴互聊","与你交流"};for(int n=0;n<filters.length;n++){final int selected=n;addRenderableWidget(Button.builder(Component.literal(filters[n]),b->{chatFilter=selected;scrollOffset=0;chatFollow=true;lastChatTail="";}).bounds(8+n*62,52,60,20).build());}
            addRenderableWidget(Button.builder(Component.literal("更早消息"),b->{chatFollow=false;ClientUi.requestHistory();}).bounds(198,52,60,20).build());
            addRenderableWidget(Button.builder(Component.literal("最新 ↓"),b->{chatFollow=true;newChat=false;scrollOffset=maxScroll(filteredChat());}).bounds(262,52,54,20).build());
        }
        int bottomY = height - 28;
        int buttonWidth = 44;
        int buttonGap = 3;
        int stopX = width - 6 - buttonWidth;
        int controlX = stopX - buttonGap - buttonWidth;
        int sendX = controlX - buttonGap - buttonWidth;
        int recipientWidth=58;
        addRenderableWidget(Button.builder(Component.literal(recipientName()),button->{
            var ids=new java.util.ArrayList<String>();ids.add("");ClientUi.snapshot().companions().forEach(c->ids.add(c.botId()));
            int next=(ids.indexOf(commandRecipient)+1)%ids.size();commandRecipient=ids.get(next);rebuildWidgets();
        }).bounds(6,bottomY,recipientWidth,20).build());
        int inputX=6+recipientWidth+buttonGap;
        int inputWidth = Math.max(30, sendX - buttonGap - inputX);
        commandBox = addRenderableWidget(new EditBox(font, inputX, bottomY, inputWidth, 20, Component.literal("输入指令")));
        commandBox.setMaxLength(512);
        commandBox.setHint(Component.literal("下达任务给"+recipientName()+"（最多512字）"));
        commandBox.setValue(draft);
        commandBox.setResponder(value -> { });
        addRenderableWidget(Button.builder(Component.literal("发送"), button -> submitCommand())
                .bounds(sendX, bottomY, buttonWidth, 20).build());
        pauseButton = addRenderableWidget(Button.builder(Component.literal(controlLabel()), button -> togglePause())
                .bounds(controlX, bottomY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("急停"), button -> ClientUi.sendControl("stop"))
                .bounds(stopX, bottomY, buttonWidth, 20).build());
        setInitialFocus(commandBox);
    }

    private String recipientName(){return commandRecipient.isBlank()?"小队 ▾":ClientUi.snapshot().companions().stream().filter(c->c.botId().equals(commandRecipient)).map(UiSnapshot.Companion::name).findFirst().orElse("未同步");}
    private void selectPage(Page target) {
        if (page == target) return;
        page = target;
        if(target==Page.CHAT){chatFollow=true;lastChatTail="";ClientUi.requestHistory();}
        scrollOffset = 0;
        rebuildWidgets();
    }

    private void submitCommand() {
        if (commandBox == null) return;
        if (ClientUi.sendCommand(commandBox.getValue(),commandRecipient)) {
            commandBox.setValue("");
            // A sent command is part of the conversation.  Move to that
            // page immediately so the owner can see it while the model turn
            // and its eventual reply are still in flight.
            page = Page.CHAT;chatFollow=true;lastChatTail="";
            scrollOffset = 0;
            rebuildWidgets();
        }
    }

    private void togglePause() {
        ClientUi.sendControl(ClientUi.snapshot().paused() ? "resume" : "pause");
    }

    private static String pageName(Page page) {
        return switch (page) {
            case TEAM -> "小队";
            case TASKS -> "任务";
            case CHAT -> "对话";
            case INVENTORY -> "背包";
            case SETTINGS -> "设置";
            case DIAGNOSTICS -> "诊断";
            case GUIDE -> "入门";
        };
    }

    private static String controlLabel() {
        return ClientUi.snapshot().paused() ? "恢复" : "暂停";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        UiSnapshot view = ClientUi.snapshot();
        if (page == Page.CHAT) {
            List<UiSnapshot.Message> messages = filteredChat();
            String tail = messages.isEmpty() ? "" : messages.get(messages.size() - 1).sequence()
                    + "|" + messages.get(messages.size() - 1).message();
            String first=messages.isEmpty()?"":messages.get(0).sequence();
            int measured=messages.stream().mapToInt(this::messageHeight).sum();
            if(!chatFollow && !chatFirst.isEmpty() && !first.equals(chatFirst) && tail.equals(lastChatTail))
                scrollOffset+=Math.max(0,measured-chatMeasuredHeight);
            chatFirst=first;chatMeasuredHeight=measured;
            if (!tail.equals(lastChatTail)) {
                lastChatTail = tail;
                if(chatFollow)scrollOffset = maxScroll(messages);else newChat=true;
            }
        }
        if(page==Page.TASKS)taskContentHeight=drawTaskBoard(null,view);
        hoveredStack = ItemStack.EMPTY;
        drawHeader(graphics, view);
        drawContent(graphics, view, mouseX, mouseY);
        if (pauseButton != null) pauseButton.setMessage(Component.literal(controlLabel()));
        for (Renderable renderable : renderables) renderable.render(graphics, mouseX, mouseY, partialTick);
        if (!hoveredStack.isEmpty()) graphics.renderTooltip(font, hoveredStack, mouseX, mouseY);
    }

    private void drawHeader(GuiGraphics graphics, UiSnapshot view) {
        graphics.fill(0, 0, width, 26, 0xD91A1A1A);
        graphics.drawString(font, Component.literal("炉火伙伴"), 8, 5, 0xFFF4D9A6);
        String game = view.diagnostics().game().status();
        String controller = ClientUi.controllerOffline() ? "控制器离线" : view.diagnostics().controller().status();
        int statusX = 82;
        if(width-223-statusX>50)graphics.drawString(font, Component.literal(clipPixels(game + " · " + controller, width-229-statusX)), statusX, 5, 0xFFD8E7D8);
        int tabGap = 2;
        int tabWidth = Math.max(42, (width - 12 - tabGap * (Page.values().length - 1)) / Page.values().length);
        int selected = page.ordinal();
        int x = 6 + selected * (tabWidth + tabGap);
        graphics.fill(x, 47, x + tabWidth, 49, 0xFFE3A84C);
        graphics.drawString(font, Component.literal(clip(ClientUi.snapshotAgeSeconds()>5?(ClientUi.controllerOffline()?"控制器离线 · ":"状态可能过期 · ")+"最后同步 "+ClientUi.snapshotAgeSeconds()+" 秒前":ClientUi.notice(), Math.max(8, (width - 62) / 9))), 8, 16, 0xFFB8C7D9);
    }

    private void drawContent(GuiGraphics graphics, UiSnapshot view, int mouseX, int mouseY) {
        graphics.fill(contentLeft, contentTop, contentRight, contentBottom, 0xB9151515);
        graphics.renderOutline(contentLeft, contentTop, contentRight - contentLeft, contentBottom - contentTop, 0x805D5D5D);
        int max = maxScroll(view);
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
        graphics.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
        switch (page) {
            case TEAM -> drawTeam(graphics, view);
            case TASKS -> drawTaskBoard(graphics, view);
            case CHAT -> drawChat(graphics, view);
            case INVENTORY -> drawInventory(graphics, view, mouseX, mouseY);
            case SETTINGS -> drawSettings(graphics, view);
            case DIAGNOSTICS -> drawDiagnostics(graphics, view);
            case GUIDE -> drawGuide(graphics);
        }
        graphics.disableScissor();
    }

    private int maxScroll(UiSnapshot view) {
        int height = contentBottom - contentTop;
        return switch (page) {
            case TEAM -> Math.max(0, teamContentHeight-height+8);
            case TASKS -> Math.max(0, taskContentHeight - height + 8);
            case CHAT -> maxScroll(filteredChat());
            case INVENTORY -> Math.max(0, inventoryLayout().bottomY() - contentBottom + 8);
            case DIAGNOSTICS -> Math.max(0, diagnosticContentHeight - height + 8);
            case GUIDE -> Math.max(0, guideHeight() - height + 12);
            default -> 0;
        };
    }

    private int maxScroll(List<UiSnapshot.Message> messages) {
        int height = contentBottom - contentTop;
        return Math.max(0, messages.stream().mapToInt(this::messageHeight).sum() - height + 12);
    }

    private void drawTeam(GuiGraphics graphics, UiSnapshot view) {
        int y = contentTop + 4 - scrollOffset;
        List<UiSnapshot.Companion> companions = view.companions();
        for (int index = 0; index < 3; index++) {
            if (index >= companions.size()) {
                card(graphics, contentLeft + 4, y + index * 48, 108, 42, index == selectedCompanion);
                graphics.drawString(font, Component.literal("伙伴待连接"), contentLeft + 10, y + index * 48 + 17, 0xFF9E9E9E);
                continue;
            }
            UiSnapshot.Companion companion = companions.get(index);
            int rowY = y + index * 48;
            card(graphics, contentLeft + 4, rowY, 108, 42, index == selectedCompanion);
            graphics.drawString(font, Component.literal(clipPixels(companion.name(), 96)), contentLeft + 10, rowY + 5, 0xFFF4D9A6);
            String activity = companion.autonomyEnabled() ? "自主 · " + activityLabel(companion.activity()) : "自主关闭";
            graphics.drawString(font, Component.literal(clipPixels(roleLabel(companion.role()) + " · " + activity, 96)), contentLeft + 10, rowY + 16, 0xFFD0D0D0);
            graphics.drawString(font, Component.literal(clipPixels("生命 " + integerText(companion.health(), false) + " 饥饿 " + integerText(companion.food(), false), 96)), contentLeft + 10, rowY + 27, 0xFFB8C7D9);
        }
        int rightX = contentLeft + 120;
        UiSnapshot.Companion selected = companions.size() > selectedCompanion ? companions.get(selectedCompanion) : null;
        graphics.drawString(font, Component.literal("当前伙伴"), rightX, contentTop + 8 - scrollOffset, 0xFFF4D9A6);
        if (selected == null) {
            graphics.drawWordWrap(font, Component.literal("正在等待伙伴连接。"), rightX, contentTop + 25 - scrollOffset, contentRight - rightX - 8, 0xFFB8C7D9);
            return;
        }
        int line = contentTop + 25 - scrollOffset;
        int detailWidth = Math.max(1, contentRight - rightX - 8);
        graphics.drawString(font, Component.literal(clipPixels("名称：" + selected.name(), detailWidth)), rightX, line, 0xFFE0E0E0); line += 12;
        graphics.drawString(font, Component.literal(clipPixels("身体：ServerPlayer · 角色：" + roleLabel(selected.role()), detailWidth)), rightX, line, 0xFFE0E0E0); line += 12;
        graphics.drawString(font, Component.literal(clipPixels("维度：" + selected.dimension(), detailWidth)), rightX, line, 0xFFE0E0E0); line += 12;
        graphics.drawString(font, Component.literal(clipPixels("位置：" + positionText(selected.position()), detailWidth)), rightX, line, 0xFFE0E0E0); line += 12;
        graphics.drawString(font, Component.literal(clipPixels("动作：" + actionLabel(selected.action()), detailWidth)), rightX, line, 0xFFE0E0E0); line += 12;
        graphics.drawString(font, Component.literal(clipPixels("自主：" + (selected.autonomyEnabled() ? "开启" : "关闭") + " · " + activityLabel(selected.activity()), detailWidth)), rightX, line, 0xFFE0E0E0); line += 12;
        UiSnapshot.Work work=view.work().stream().filter(w->w.botId().equals(selected.botId())).findFirst().orElse(null);
        Component goal = Component.literal("目标：" + (work==null?"尚未同步":work.goal()+"\n阶段："+work.stage()+"\n当前步骤："+work.step()+"\n进度："+work.progress()));
        graphics.drawWordWrap(font, goal, rightX, line, contentRight - rightX - 8, 0xFFE0E0E0);
        line += Math.max(1, font.split(goal, contentRight - rightX - 8).size()) * font.lineHeight;
        if (!UiSnapshot.UNKNOWN.equals(selected.waitReason())) {
            Component waiting = Component.literal("等待：" + clip(selected.waitReason(), 96));
            graphics.drawWordWrap(font, waiting, rightX, line, contentRight - rightX - 8, 0xFFFFD27D);
            line += Math.max(1,font.split(waiting,detailWidth).size())*font.lineHeight;
        }
        if (!UiSnapshot.UNKNOWN.equals(selected.recoverySummary())) {
            graphics.drawWordWrap(font,Component.literal(selected.recoverySummary()),rightX,line+4,detailWidth,0xFFB8C7D9);
            line+=font.split(Component.literal(selected.recoverySummary()),detailWidth).size()*font.lineHeight+4;
        }
        teamContentHeight=Math.max(150,line+scrollOffset-contentTop+8);
    }

    private String displayText(String value){
        if(value==null||value.isBlank()||UiSnapshot.UNKNOWN.equals(value))return "未同步";
        var matcher=java.util.regex.Pattern.compile("minecraft:[a-z0-9_]+").matcher(value);var out=new StringBuffer();
        while(matcher.find()){String key=matcher.group();var location=ResourceLocation.tryParse(key);String shown=key;if(location!=null&&BuiltInRegistries.ITEM.containsKey(location))shown=new ItemStack(BuiltInRegistries.ITEM.get(location)).getHoverName().getString();matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(shown));}matcher.appendTail(out);return out.toString();
    }
    private int paragraph(GuiGraphics g,String text,int x,int y,int w,int color,int maxLines){
        var lines=font.split(Component.literal(displayText(text)),Math.max(1,w));int count=maxLines>0?Math.min(maxLines,lines.size()):lines.size();
        if(g!=null)for(int n=0;n<count;n++){
            if(maxLines>0&&n==count-1&&lines.size()>count){
                var plain=new StringBuilder();lines.get(n).accept((index,style,codepoint)->{plain.appendCodePoint(codepoint);return true;});
                g.drawString(font,Component.literal(font.plainSubstrByWidth(plain.toString(),Math.max(1,w-font.width("…")))+"…"),x,y+n*font.lineHeight,color);
            }else g.drawString(font,lines.get(n),x,y+n*font.lineHeight,color);
        }
        return Math.max(1,count)*font.lineHeight+5;
    }
    private int drawTaskBoard(GuiGraphics g,UiSnapshot view){
        if(g!=null)taskHits.clear();int top=contentTop+6-scrollOffset,y=top,w=contentRight-contentLeft-16,x=contentLeft+8;
        if(taskBot.isBlank()){
            var cards=io.github.yufeiyufei888.hearthcrew.kernel.TaskBoardLayout.cards(x,y,w,3,160);
            String[] names={"Ember","Moss","Flint"};
            for(int n=0;n<3;n++){var c=cards.get(n);final String name=names[n];var work=view.work().stream().filter(v->name.equals(v.name())).findFirst().orElse(null);
                if(g!=null){card(g,c.x(),c.y(),c.width(),c.height(),false);if(work!=null)taskHits.add(new TaskHit(c.x(),c.y(),c.width(),c.height(),()->{taskBot=work.botId();scrollOffset=0;ClientUi.requestStages(taskBot,false);}));}
                int at=c.y()+7,cx=c.x()+7,cw=c.width()-14;
                at+=paragraph(g,name+" · "+(work==null?"伙伴待连接":activityLabel(work.state())),cx,at,cw,0xFFF4D9A6,1);
                if(work!=null){
                    at+=paragraph(g,"目标："+work.goal(),cx,at,cw,0xFFFFFFFF,2);
                    at+=paragraph(g,"阶段："+work.stage(),cx,at,cw,0xFFB8C7D9,2);
                    at+=paragraph(g,"正在："+work.step(),cx,at,cw,0xFFBDE3BC,2);
                    at+=paragraph(g,work.waitReason().isBlank()?work.progress():"受阻："+work.waitReason(),cx,at,cw,0xFFFFD27D,2);
                    paragraph(g,"点击查看详情 →",cx,c.y()+145,cw,0xFFB8C7D9,1);
                }else paragraph(g,"加入伙伴后显示目标与进展",cx,at,cw,0xFFB8C7D9,2);
            }return cards.getLast().y()+cards.getLast().height()-top+12;
        }
        var work=view.work().stream().filter(v->v.botId().equals(taskBot)).findFirst().orElse(null);
        if(work==null)return paragraph(g,"伙伴状态未同步，请返回总览",x,y,w,0xFFFFD27D,0);
        y+=paragraph(g,work.name()+" · "+activityLabel(work.state())+" · "+work.source(),x,y,w,0xFFF4D9A6,0);
        y+=paragraph(g,"总目标\n"+work.fullGoal(),x,y+4,w,0xFFFFFFFF,0)+8;
        y+=paragraph(g,"当前阶段\n"+work.fullStage()+"\n完成条件："+work.completion(),x,y,w,0xFFB8C7D9,0)+6;
        y+=paragraph(g,"当前步骤\n"+work.step()+"\n"+work.progress(),x,y,w,0xFFBDE3BC,0)+6;
        if(!work.waitReason().isBlank())y+=paragraph(g,"受阻原因："+work.waitReason(),x,y,w,0xFFFFD27D,0)+6;
        y+=paragraph(g,"待办请求",x,y,w,0xFFF4D9A6,0);
        if(work.pending().isEmpty())y+=paragraph(g,"暂无排队请求",x,y,w,0xFFB8C7D9,0);
        for(var request:work.pending())y+=paragraph(g,"• "+request,x,y,w,0xFFE0E0E0,0);
        y+=paragraph(g,"阶段历史（动作尝试属于阶段，不是新的任务）",x,y+10,w,0xFFF4D9A6,0)+10;
        for(var stage:ClientUi.stages(taskBot)){
            y+=paragraph(g,stage.title()+" · "+stateLabel(stage.state()),x,y,w,0xFFFFFFFF,0);
            y+=paragraph(g,stage.summary()+"\n条件："+stage.completion(),x+6,y,w-6,0xFFB8C7D9,0);
            if(!stage.reason().isBlank()&&!UiSnapshot.UNKNOWN.equals(stage.reason()))y+=paragraph(g,"原因："+stage.reason(),x+6,y,w-6,0xFFFFD27D,0);
            for(var attempt:stage.attempts())y+=paragraph(g,"最近尝试："+attempt,x+6,y,w-6,0xFFA5A5A5,0);
            y+=10;
        }
        y+=paragraph(g,ClientUi.nextStages(taskBot)<0?"已显示全部阶段":"点击上方“读取阶段历史”加载更多",x,y,w,0xFFB8C7D9,0);
        return y-top+8;
    }
    private List<UiSnapshot.Message> filteredChat(){return ClientUi.chatMessages().stream().filter(m->{boolean owner="player".equals(m.origin())||"owner.command".equals(m.type())||m.recipients().stream().anyMatch(n->!java.util.Set.of("Ember","Moss","Flint","小队","team","all").contains(n));return chatFilter==0||chatFilter==1&&!owner||chatFilter==2&&owner;}).toList();}
    private String messageTime(String value){try{return java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));}catch(RuntimeException e){return "时间未同步";}}
    private void drawChat(GuiGraphics graphics, UiSnapshot view) {
        int y = contentTop + 6 - scrollOffset;
        List<UiSnapshot.Message> messages = filteredChat();
        for (UiSnapshot.Message message : messages) {
            String prefix = messageTime(message.atUtc())+"  "+speakerLabel(view, message);
            if("player".equals(message.origin()))prefix+=" · "+stateLabel(message.state());
            if ("game.event".equals(message.type())) prefix = "任务进展 · " + stateLabel(message.state());
            int color = "role.failure".equals(message.type()) ? 0xFFFF9A9A
                    : "owner.command".equals(message.type()) ? 0xFF9BE7FF : 0xFFF4D9A6;
            graphics.drawString(font, Component.literal(clipPixels(prefix,contentRight-contentLeft-16)), contentLeft + 8, y, color);
            graphics.drawWordWrap(font, Component.literal(chatText(message)), contentLeft + 8, y + 10, contentRight - contentLeft - 16, 0xFFE0E0E0);
            y += messageHeight(message);
        }
        if(newChat)graphics.drawString(font,Component.literal("有新消息 · 点击上方最新 ↓"),contentLeft+8,contentBottom-12,0xFFFFD27D);
        if (messages.isEmpty()) graphics.drawString(font, Component.literal("暂无真实发言；这里不显示动作与系统日志"), contentLeft + 8, y, 0xFF9E9E9E);
    }

    private String chatText(UiSnapshot.Message m){return (!UiSnapshot.UNKNOWN.equals(m.replyTo())&&!m.replyTo().isBlank()?"回复 "+(UiSnapshot.UNKNOWN.equals(m.replyName())?"先前消息":m.replyName())+"：\n":"")+m.message()+(!UiSnapshot.UNKNOWN.equals(m.statusReason())&&!m.statusReason().isBlank()?"\n发送说明："+m.statusReason():"");}
    private String speakerLabel(UiSnapshot view, UiSnapshot.Message message) {
        if ("crew.chat".equals(message.type())) return message.role() + " → " + (message.recipients().isEmpty() ? "小队" : String.join("、", message.recipients()));
        if ("owner.command".equals(message.type())) {
            String state = stateLabel(message.state());
            return "你 → "+(!message.recipients().isEmpty()?String.join("、",message.recipients()):view.companions().stream().filter(c->c.botId().equals(message.botId())).map(UiSnapshot.Companion::name).findFirst().orElse("小队"));
        }
        if ("role.failure".equals(message.type()) && "系统".equals(message.role())) return "系统";
        String name = view.companions().stream()
                .filter(companion -> !UiSnapshot.UNKNOWN.equals(message.botId())
                        && companion.botId().equals(message.botId()))
                .map(UiSnapshot.Companion::name)
                .filter(candidate -> !UiSnapshot.UNKNOWN.equals(candidate))
                .findFirst().orElse("");
        String sender=name.isBlank()?"发言者未同步":name;
        String recipients=message.recipients().isEmpty()?"小队":String.join("、",message.recipients().stream().map(target->view.companions().stream()
                .filter(c->c.botId().equals(target)||c.role().equals(target)||c.name().equals(target)).map(UiSnapshot.Companion::name).findFirst().orElse("team".equals(target)?"小队":target)).toList());
        return sender+" → "+recipients;
    }

    private int messageHeight(UiSnapshot.Message message) {
        return 18 + Math.max(1, font.split(Component.literal(chatText(message)), Math.max(40, contentRight - contentLeft - 16)).size()) * font.lineHeight;
    }

    private void drawInventory(GuiGraphics graphics, UiSnapshot view, int mouseX, int mouseY) {
        List<UiSnapshot.Companion> companions = view.companions();
        InventoryLayout layout = inventoryLayout();
        int y = layout.selectorY() - scrollOffset;
        int selectorHeight = layout.horizontalSelectors() ? 30 : 42;
        for (int index = 0; index < 3; index++) {
            int selectorX = layout.horizontalSelectors()
                    ? contentLeft + 4 + index * (layout.selectorWidth() + 2) : contentLeft + 4;
            int selectorY = layout.horizontalSelectors() ? y : y + index * 48;
            card(graphics, selectorX, selectorY, layout.horizontalSelectors() ? layout.selectorWidth() : 108,
                    selectorHeight, index == selectedCompanion);
            String name = companions.size() > index ? companions.get(index).name() : "伙伴待连接";
            graphics.drawString(font, Component.literal(clip(name, layout.horizontalSelectors() ? 14 : 10)),
                    selectorX + 6, selectorY + (layout.horizontalSelectors() ? 10 : 16), 0xFFE0E0E0);
        }
        if (companions.size() <= selectedCompanion) return;
        UiSnapshot.Companion companion = companions.get(selectedCompanion);
        int slotSize = layout.slotSize();
        int slotStep = layout.slotStep();
        int gridX = layout.gridX();
        int gridY = layout.mainY() - scrollOffset;
        graphics.drawString(font, Component.literal("只读库存：" + companion.name()), gridX, layout.titleY() - scrollOffset, 0xFFF4D9A6);
        graphics.drawString(font, Component.literal("背包"), gridX, layout.labelY() - scrollOffset, 0xFFB8C7D9);
        for (int slot = 9; slot < 36; slot++) {
            int col = slot - 9;
            int row = col / 9;
            drawInventorySlot(graphics, companion, slot, gridX + (col % 9) * slotStep,
                    gridY + row * slotStep, slotSize, false, mouseX, mouseY);
        }
        int hotbarY = layout.hotbarY() - scrollOffset;
        graphics.drawString(font, Component.literal("快捷栏"), gridX, layout.hotbarLabelY() - scrollOffset, 0xFFB8C7D9);
        for (int slot = 0; slot < 9; slot++) {
            drawInventorySlot(graphics, companion, slot, gridX + slot * slotStep, hotbarY,
                    slotSize, slot == companion.selectedSlot(), mouseX, mouseY);
        }
        graphics.drawString(font, Component.literal("选中：" + (companion.selectedSlot() >= 0
                ? "快捷栏 " + (companion.selectedSlot() + 1) : "未同步")), gridX, hotbarY + slotStep + 2, 0xFF9E9E9E);
        int equipmentX = layout.equipmentX();
        graphics.drawString(font, Component.literal("装备"), equipmentX - 16, layout.labelY() - scrollOffset, 0xFFB8C7D9);
        String[] equipmentKeys = {"head", "chest", "legs", "feet", "offhand"};
        String[] equipmentLabels = {"头", "胸", "腿", "脚", "副"};
        if (!companion.equipmentKnown()) {
            graphics.drawString(font, Component.literal("未同步"), equipmentX - 12, gridY + 5 * slotStep + 3, 0xFFFFD27D);
        }
        for (int index = 0; index < equipmentKeys.length; index++) {
            int x = equipmentX;
            int itemY = gridY + index * slotStep;
            graphics.fill(x, itemY, x + slotSize, itemY + slotSize, 0xFF303030);
            graphics.renderOutline(x, itemY, slotSize, slotSize, 0xFF686868);
            UiSnapshot.EquippedItem data = companion.equipment().get(equipmentKeys[index]);
            if (data != null && data.count() > 0 && !UiSnapshot.UNKNOWN.equals(data.item())) {
                ItemStack stack = readOnlyItem(data.item(), data.count());
                if (stack.isEmpty()) graphics.drawString(font, Component.literal("?"), x + 7, itemY + 6, 0xFFE08080);
                else {
                    renderSlotItem(graphics, stack, x, itemY, slotSize);
                    if (mouseX >= x && mouseX < x + slotSize && mouseY >= itemY && mouseY < itemY + slotSize) hoveredStack = stack;
                }
            }
            graphics.drawString(font, Component.literal(equipmentLabels[index]), x - 16, itemY + 6, 0xFF9E9E9E);
        }
    }

    private InventoryLayout inventoryLayout() {
        int availableWidth = contentRight - contentLeft;
        int equipmentWidth = 40; // 18px slot plus left label and spacing
        int selectorGap = 16;
        boolean horizontal = availableWidth < 108 + selectorGap + equipmentWidth + 9 * 18;
        int slotStep = horizontal
                ? Math.min(18, Math.max(12, (availableWidth - 56) / 9)) : 18;
        int slotSize = slotStep;
        int gridWidth = slotStep * 9;
        int selectorY = contentTop + 4;
        int titleY;
        int labelY;
        int mainY;
        if (horizontal) {
            titleY = contentTop + 50;
            labelY = contentTop + 66;
            mainY = contentTop + 78;
        } else {
            titleY = contentTop + 5;
            labelY = contentTop + 20;
            mainY = contentTop + 32;
        }
        int hotbarLabelY = mainY + slotStep * 3 + 7;
        int hotbarY = hotbarLabelY + 12;
        int bottomY = Math.max(hotbarY + slotSize + 14, mainY + 5 * slotStep + 16);
        int gridX = Math.max(contentLeft + (horizontal ? 48 : 154), contentRight - gridWidth - 8);
        int equipmentX = gridX - 24;
        int selectorWidth = horizontal ? Math.max(48, (availableWidth - 12) / 3) : 108;
        return new InventoryLayout(horizontal, selectorWidth, selectorY, titleY, labelY, mainY,
                hotbarLabelY, hotbarY, bottomY, gridX, equipmentX, slotSize, slotStep);
    }

    private void drawInventorySlot(GuiGraphics graphics, UiSnapshot.Companion companion, int slot,
                                   int x, int itemY, int slotSize, boolean selected,
                                   int mouseX, int mouseY) {
        graphics.fill(x, itemY, x + slotSize, itemY + slotSize, selected ? 0xFF61471F : 0xFF303030);
        graphics.renderOutline(x, itemY, slotSize, slotSize, selected ? 0xFFE3A84C : 0xFF686868);
        UiSnapshot.InventorySlot data = companion.inventory().stream().filter(item -> item.slot() == slot).findFirst().orElse(null);
        if (data == null) return;
        ItemStack stack = readOnlyItem(data.item(), data.count());
        if (stack.isEmpty()) {
            graphics.drawString(font, Component.literal("?"), x + 7, itemY + 6, 0xFFE08080);
        } else {
            renderSlotItem(graphics, stack, x, itemY, slotSize);
            if (mouseX >= x && mouseX < x + slotSize && mouseY >= itemY && mouseY < itemY + slotSize) hoveredStack = stack;
        }
    }

    private void renderSlotItem(GuiGraphics graphics, ItemStack stack, int x, int y, int slotSize) {
        float scale = Math.min(1F, (slotSize - 2) / 16F);
        graphics.pose().pushPose();
        graphics.pose().translate(x + 1, y + 1, 0);
        graphics.pose().scale(scale, scale, 1F);
        graphics.renderItem(stack, 0, 0);
        graphics.renderItemDecorations(font, stack, 0, 0);
        graphics.pose().popPose();
    }

    private void drawSettings(GuiGraphics graphics, UiSnapshot view) {
        int y = contentTop + 8 - scrollOffset;
        graphics.drawString(font, Component.literal("设置"), contentLeft + 8, y, 0xFFF4D9A6); y += 18;
        boolean autonomy = view.companions().stream().anyMatch(UiSnapshot.Companion::autonomyEnabled);
        settingLine(graphics, "自主游玩", autonomy ? "开启（由三名 Luna 独立规划）" : "关闭", y); y += 15;
        graphics.drawWordWrap(font, Component.literal("当前伙伴的运行配置"), contentLeft + 8, y, contentRight - contentLeft - 16, 0xFFE0E0E0); y += 25;
        UiSnapshot.Layer app = view.diagnostics().appServer();
        settingLine(graphics, "App Server", app.status(), y); y += 15;
        if (view.diagnostics().roles().isEmpty()) {
            settingLine(graphics, "角色配置", UiSnapshot.UNKNOWN, y); y += 15;
        } else {
            for (UiSnapshot.Role role : view.diagnostics().roles()) {
                String configuration = role.model() + " / " + role.effort() + " / " + role.serviceTier();
                settingLine(graphics, roleLabel(role.role()), configuration, y); y += 15;
            }
        }
        settingLine(graphics, "快捷键", "H（可在 Minecraft 控制设置中重绑定）", y); y += 15;
        settingLine(graphics, "附近感知", "最多 32 格", y);
    }

    private void drawDiagnostics(GuiGraphics graphics, UiSnapshot view) {
        int y = contentTop + 6 - scrollOffset;
        y = layer(graphics, "游戏", view.diagnostics().game(), y);
        y = layer(graphics, "控制器", view.diagnostics().controller(), y);
        y = layer(graphics, "App Server", view.diagnostics().appServer(), y);
        graphics.drawString(font, Component.literal("角色连接"), contentLeft + 8, y, 0xFFF4D9A6); y += 14;
        for (UiSnapshot.Role role : view.diagnostics().roles()) {
            int textWidth = Math.max(1, contentRight - contentLeft - 24);
            for (String value : new String[] {role.name() + " · " + roleLabel(role.role()) + " · " + stateLabel(role.status()),
                    role.model() + " / " + role.effort() + " / " + role.serviceTier(), "会话：" + role.threadId(), "状态：" + activityLabel(role.activity()), "原因：" + role.waitReason()}) {
                var line = Component.literal(value);
                graphics.drawWordWrap(font, line, contentLeft + 12, y, textWidth, 0xFFE0E0E0);
                y += Math.max(1, font.split(line, textWidth).size()) * font.lineHeight + 3;
            }
        }
        graphics.drawString(font, Component.literal("快照 " + (view.truncated() ? "已截断" : "完整") + " · schemaVersion=" + view.schemaVersion()), contentLeft + 8, y + 6, 0xFF9E9E9E);
        diagnosticContentHeight = y + scrollOffset - contentTop + 24;
    }

    private void drawGuide(GuiGraphics graphics) {
        int y = contentTop + 8 - scrollOffset;
        for (int index = 0; index < GUIDE_TEXT.size(); index++) {
            int color = index == 0 || index == 4 || index == 6 ? 0xFFF4D9A6
                    : index == 5 || index == GUIDE_TEXT.size() - 1 ? 0xFFB8C7D9 : 0xFFE0E0E0;
            y = guideParagraph(graphics, GUIDE_TEXT.get(index), y, color, index == 0 || index == 6);
        }
    }

    private int guideParagraph(GuiGraphics graphics, String text, int y, int color, boolean heading) {
        int maxWidth = Math.max(80, contentRight - contentLeft - 16);
        Component component = Component.literal(text);
        graphics.drawWordWrap(font, component, contentLeft + 8, y, maxWidth, color);
        return y + Math.max(1, font.split(component, maxWidth).size()) * font.lineHeight + (heading ? 8 : 5);
    }

    private int guideHeight() {
        int maxWidth = Math.max(80, contentRight - contentLeft - 16);
        int y = contentTop + 8;
        for (int index = 0; index < GUIDE_TEXT.size(); index++) {
            Component component = Component.literal(GUIDE_TEXT.get(index));
            y += Math.max(1, font.split(component, maxWidth).size()) * font.lineHeight
                    + ((index == 0 || index == 6) ? 8 : 5);
        }
        return y - contentTop;
    }

    private void copyCodexPrompt() {
        Minecraft.getInstance().keyboardHandler.setClipboard(CODEX_PROMPT);
        ClientUi.setNotice("接入提示词已复制，可粘贴到 Codex 桌面端");
    }

    private int layer(GuiGraphics graphics, String name, UiSnapshot.Layer layer, int y) {
        graphics.drawString(font, Component.literal(name + "：" + layer.status()), contentLeft + 8, y, 0xFFF4D9A6);
        y += 12;
        int shown = 0;
        for (var entry : layer.details().entrySet()) {
            String label = switch (entry.getKey()) { case "quietProgress" -> "静默记录"; case "version" -> "版本"; case "lastError" -> "最近错误"; default -> entry.getKey(); };
            y += paragraph(graphics, label + "：" + entry.getValue(), contentLeft + 14, y,
                    Math.max(1, contentRight - contentLeft - 28), 0xFFD0D0D0, 0) + 2;
            if (++shown == 3) break;
        }
        return y + 5;
    }

    private void settingLine(GuiGraphics graphics, String label, String value, int y) {
        graphics.drawString(font, Component.literal(label + "：" + value), contentLeft + 8, y, 0xFFE0E0E0);
    }

    private void card(GuiGraphics graphics, int x, int y, int width, int height, boolean selected) {
        graphics.fill(x, y, x + width, y + height, selected ? 0xFF4A3824 : 0xFF252525);
        graphics.renderOutline(x, y, width, height, selected ? 0xFFE3A84C : 0xFF555555);
    }

    private static String positionText(UiSnapshot.Position position) {
        return position.known() ? integerText(position.x(), true) + ", " + integerText(position.y(), true) + ", " + integerText(position.z(), true) : UiSnapshot.UNKNOWN;
    }

    /** Presentation only: coordinates use block-floor, health rounds up like hearts. */
    private static String integerText(String value, boolean blockCoordinate) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) return UiSnapshot.UNKNOWN;
            return Long.toString((long)(blockCoordinate ? Math.floor(number) : Math.ceil(number)));
        } catch (RuntimeException invalid) { return UiSnapshot.UNKNOWN; }
    }

    private String clipPixels(String value, int availableWidth) {
        String text = value == null ? UiSnapshot.UNKNOWN : value;
        if (font.width(text) <= availableWidth) return text;
        String suffix = "…";
        if (availableWidth < font.width(suffix)) return "";
        return font.plainSubstrByWidth(text, availableWidth - font.width(suffix)) + suffix;
    }

    private static String clip(String value, int maximumCharacters) {
        if (value == null) return UiSnapshot.UNKNOWN;
        return value.length() <= maximumCharacters ? value : value.substring(0, Math.max(0, maximumCharacters - 1)) + "…";
    }

    private static String roleLabel(String role) {
        return switch (role == null ? "" : role.toLowerCase(java.util.Locale.ROOT)) {
            case "coordinator" -> "协调防卫";
            case "gatherer" -> "探索采集";
            case "builder" -> "建设后勤";
            default -> role == null || role.isBlank() ? UiSnapshot.UNKNOWN : role;
        };
    }

    private static String actionLabel(String action) {
        return switch (action == null ? "" : action.toUpperCase(java.util.Locale.ROOT)) {
            case "MINE" -> "按方块采集";
            case "COLLECT_RESOURCE" -> "按物品持续采集";
            case "SEQUENCE" -> "连续准备步骤";
            case "YIELD" -> "让路";
            case "CRAFT" -> "制作";
            case "TRANSFER" -> "交接";
            case "MOVE" -> "移动";
            case "BUILD", "PLACE" -> "建造";
            case "GUARD" -> "护卫";
            case "SELF_DEFENCE" -> "本地自卫";
            case "BREATHE" -> "水中求生与上岸";
            case "SLEEP" -> "睡眠";
            case "EAT" -> "进食";
            case "WAIT", "IDLE" -> "等待";
            default -> action == null || action.isBlank() ? UiSnapshot.UNKNOWN : action;
        };
    }

    private static String stateLabel(String state) {
        return switch (state == null ? "" : state.toUpperCase(java.util.Locale.ROOT)) {
            case "CONTINUE" -> "进行中";
            case "THINKING" -> "等待模型";
            case "RECOVERY_ASSESSMENT" -> "重生／恢复评估";
            case "EXCAVATION_SCANNING" -> "开路搜索";
            case "EXCAVATING", "ACCESS_EXCAVATING" -> "挖掘开路";
            case "ACCESS_SEARCHING" -> "搜索安全开路";
            case "ACCESS_APPROACHING", "EXCAVATION_FOLLOWING" -> "沿通道接近目标";
            case "RECOVERING_DROPS", "PICKUP_DELAYED" -> "回收掉落物";
            case "EXPLORATION_OBSERVING" -> "终点环境观察";
            case "RECOVERING" -> "恢复中";
            case "SCANNING" -> "扫描中";
            case "MINING_SCANNING" -> "扫描采掘范围";
            case "PATH_SEARCHING", "PICKUP_PATH_SEARCHING", "BOAT_STANCE_SEARCHING", "COMBAT_PATH_SEARCHING" -> "寻路中";
            case "MINING" -> "批量采掘";
            case "AWAITING_PICKUP" -> "回收掉落物";
            case "AWAITING_CHUNK_TICK" -> "等待附近区块运行";
            case "WAITING_PLACEMENT_CLEARANCE" -> "等待施工位置空出";
            case "SWIMMING" -> "游泳";
            case "SURFACING" -> "上浮";
            case "SEEKING_SHORE" -> "游向岸边";
            case "SHORE_SEARCHING" -> "搜索可上岸位置";
            case "SHORE_SETTLING" -> "确认已站稳上岸";
            case "SURFACE_SUPPORT" -> "保持水面等待";
            case "PATH_BLOCKED", "SURFACING_BLOCKED" -> "路径受阻";
            case "REPLANNING" -> "重新规划路径";
            case "WALKING" -> "行走";
            case "IDLE" -> "空闲";
            case "PAUSED" -> "已暂停";
            case "STOPPED" -> "已急停";
            case "CONNECTED" -> "已连接";
            case "UNKNOWN" -> "待确认";
            case "SENDING" -> "发送中";
            case "RUNNING" -> "执行中";
            case "ACCEPTED" -> "已接受";
            case "COMPLETED", "COMPLETE" -> "已完成";
            case "FAILED", "BLOCKED" -> "失败";
            case "CANCELLED" -> "已取消";
            case "PARTIAL" -> "部分完成";
            case "RECONCILE_REQUIRED" -> "待核对";
            case "AWAITING_BODY" -> "等待身体";
            case "AUTONOMOUS", "AUTONOMY" -> "自主游玩";
            case "STANDBY", "WAITING" -> "待命";
            default -> state == null || state.isBlank() ? UiSnapshot.UNKNOWN : state;
        };
    }

    @Nullable
    private static ItemStack readOnlyItem(String id, int count) {
        if (count < 1 || id == null || UiSnapshot.UNKNOWN.equals(id)) return ItemStack.EMPTY;
        try {
            ResourceLocation key = ResourceLocation.parse(id);
            return BuiltInRegistries.ITEM.getOptional(key).map(item -> new ItemStack(item, count)).orElse(ItemStack.EMPTY);
        } catch (RuntimeException invalid) {
            return ItemStack.EMPTY;
        }
    }

    private static String activityLabel(String activity) {
        if (activity == null || activity.isBlank() || UiSnapshot.UNKNOWN.equals(activity)) return "未同步";
        return switch (activity.toUpperCase(java.util.Locale.ROOT)) {
            case "CONTINUE" -> "进行中";
            case "THINKING" -> "等待模型";
            case "RECOVERY_ASSESSMENT" -> "重生／恢复评估";
            case "EXCAVATION_SCANNING" -> "开路搜索";
            case "EXCAVATING", "ACCESS_EXCAVATING" -> "挖掘开路";
            case "ACCESS_SEARCHING" -> "搜索安全开路";
            case "ACCESS_APPROACHING", "EXCAVATION_FOLLOWING" -> "沿通道接近目标";
            case "RECOVERING_DROPS", "PICKUP_DELAYED" -> "回收掉落物";
            case "EXPLORATION_OBSERVING" -> "终点环境观察";
            case "RECOVERING" -> "恢复中";
            case "SCANNING" -> "扫描中";
            case "MINING_SCANNING" -> "扫描采掘范围";
            case "PATH_SEARCHING", "PICKUP_PATH_SEARCHING", "BOAT_STANCE_SEARCHING", "COMBAT_PATH_SEARCHING" -> "寻路中";
            case "MINING" -> "批量采掘";
            case "AWAITING_PICKUP" -> "回收掉落物";
            case "AWAITING_CHUNK_TICK" -> "等待附近区块运行";
            case "WAITING_PLACEMENT_CLEARANCE" -> "等待施工位置空出";
            case "SWIMMING" -> "游泳";
            case "SURFACING" -> "上浮";
            case "SEEKING_SHORE" -> "游向岸边";
            case "SHORE_SEARCHING" -> "搜索可上岸位置";
            case "SHORE_SETTLING" -> "确认已站稳上岸";
            case "SURFACE_SUPPORT" -> "保持水面等待";
            case "PATH_BLOCKED", "SURFACING_BLOCKED" -> "路径受阻";
            case "REPLANNING" -> "重新规划路径";
            case "WALKING" -> "行走";
            case "WAITING", "WAIT_CONDITION" -> "等待条件";
            case "STANDBY", "IDLE" -> "待命";
            case "AUTONOMOUS" -> "自主游玩";
            case "WORKING" -> "执行中";
            case "DEFENDING" -> "迎敌反击";
            case "RETREATING" -> "危险撤退";
            case "SAFETY_WATCH" -> "确认安全";
            case "FAILED", "BLOCKED" -> "失败，查看原因";
            case "PAUSED" -> "已暂停";
            case "STOPPED" -> "已停止";
            case "DISCONNECTED" -> "连接断开";
            case "AWAITING_BODY" -> "等待身体";
            case "TEAM_TASK", "TEAM" -> "团队任务";
            case "PLAYER_COMMAND", "COMMAND", "OWNER" -> "执行指令";
            case "RECONCILE_REQUIRED" -> "待核对";
            default -> activity;
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if(page==Page.TASKS&&mouseY>=contentTop&&mouseY<contentBottom)for(var hit:taskHits)if(mouseX>=hit.x()&&mouseX<hit.x()+hit.width()&&mouseY>=hit.y()&&mouseY<hit.y()+hit.height()){hit.click().run();return true;}
        if (button == 0 && page == Page.INVENTORY) {
            InventoryLayout layout = inventoryLayout();
            double adjustedY = mouseY + scrollOffset;
            if (layout.horizontalSelectors()) {
                int selectorTop = layout.selectorY();
                if (adjustedY >= selectorTop && adjustedY < selectorTop + 30
                        && mouseX >= contentLeft + 4 && mouseX < contentRight - 4) {
                    int candidate = (int) ((mouseX - contentLeft - 4) / (layout.selectorWidth() + 2));
                    if (candidate >= 0 && candidate < 3) {
                        selectedCompanion = candidate;
                        return true;
                    }
                }
            } else if (mouseX >= contentLeft && mouseX < contentLeft + 116
                    && adjustedY >= contentTop && adjustedY < contentTop + 150) {
                int candidate = (int) ((adjustedY - contentTop - 4) / 48);
                if (candidate >= 0 && candidate < 3) {
                    selectedCompanion = candidate;
                    return true;
                }
            }
        }
        if (button == 0 && page == Page.TEAM
                && mouseX >= contentLeft && mouseX < contentLeft + 116
                && mouseY >= contentTop && mouseY < contentTop + 150) {
            int candidate = (int) ((mouseY - contentTop + scrollOffset - 4) / 48);
            if (candidate >= 0 && candidate < 3) {
                selectedCompanion = candidate;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= contentLeft && mouseX < contentRight && mouseY >= contentTop && mouseY < contentBottom) {
            int max = page == Page.CHAT ? maxScroll(filteredChat()) : maxScroll(ClientUi.snapshot());
            scrollOffset = Math.max(0, Math.min(max, scrollOffset - (int) Math.round(verticalAmount * 18.0D)));
            if(page==Page.CHAT){chatFollow=scrollOffset>=max-2;if(chatFollow)newChat=false;}
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
