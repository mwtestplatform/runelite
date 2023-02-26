/*
 * Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
 * Copyright (c) 2018, Shaun Dreclin <shaundreclin@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.slayer;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Binder;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Named;
import joptsimple.internal.Strings;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.ItemID;
import net.runelite.api.MessageNode;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatClient;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ChatInput;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.ArrayUtils;

@PluginDescriptor(
	name = "Slayer",
	description = "Show additional slayer task related information",
	tags = {"combat", "notifications", "overlay", "tasks"}
)
@Slf4j
public class SlayerPlugin extends Plugin
{
	//Chat messages
	private static final String CHAT_SUPERIOR_MESSAGE = "A superior foe has appeared...";

	// Chat Command
	private static final String TASK_COMMAND_STRING = "!task";
	private static final Pattern TASK_STRING_VALIDATION = Pattern.compile("[^a-zA-Z0-9' -]");
	private static final int TASK_STRING_MAX_LENGTH = 50;

	@Inject
	private Client client;

	@Inject
	private SlayerConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SlayerOverlay overlay;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TargetWeaknessOverlay targetWeaknessOverlay;

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ChatClient chatClient;

	@Inject
	private NpcOverlayService npcOverlayService;

	@Getter(AccessLevel.PACKAGE)
	private final List<NPC> targets = new ArrayList<>();

	@Inject
	@Named("developerMode")
	boolean developerMode;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private int amount;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private int initialAmount;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private String taskLocation;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private String taskName;

	private TaskCounter counter;
	private Instant infoTimer;
	private boolean loginFlag;
	private final List<Pattern> targetNames = new ArrayList<>();

	private String[] taskLocations;

	public final Function<NPC, HighlightedNpc> isTarget = (n) ->
	{
		if ((config.highlightHull() || config.highlightTile() || config.highlightOutline()) && targets.contains(n))
		{
			Color color = config.getTargetColor();
			return HighlightedNpc.builder()
				.npc(n)
				.highlightColor(color)
				.fillColor(ColorUtil.colorWithAlpha(color, color.getAlpha() / 12))
				.hull(config.highlightHull())
				.tile(config.highlightTile())
				.outline(config.highlightOutline())
				.build();

		}
		return null;
	};

	@Override
	public void configure(Binder binder)
	{
		binder.bind(SlayerPluginService.class).to(SlayerPluginServiceImpl.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		chatCommandManager.registerCommandAsync(TASK_COMMAND_STRING, this::taskLookup, this::taskSubmit);
		npcOverlayService.registerHighlighter(isTarget);

		overlayManager.add(overlay);
		overlayManager.add(targetWeaknessOverlay);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			loginFlag = true;
			clientThread.invoke(this::updateTask);
		}

		clientThread.invoke(() ->
		{
			if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState())
			{
				return false;
			}

			// !task requires off-thread access to slayer task locations
			EnumComposition e = client.getEnum(EnumID.SLAYER_TASK_LOCATION);
			taskLocations = e.getStringVals().clone();
			return true;
		});
	}

	@Override
	protected void shutDown() throws Exception
	{
		chatCommandManager.unregisterCommand(TASK_COMMAND_STRING);
		npcOverlayService.unregisterHighlighter(isTarget);

		overlayManager.remove(overlay);
		overlayManager.remove(targetWeaknessOverlay);
		removeCounter();
		targets.clear();

		taskLocations = null;
	}

	@Provides
	SlayerConfig provideSlayerConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case HOPPING:
			case LOGGING_IN:
				taskName = "";
				amount = 0;
				loginFlag = true;
				targets.clear();
				break;
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted)
	{
		if (developerMode && commandExecuted.getCommand().equals("task"))
		{
			setTask(commandExecuted.getArguments()[0], 42, 42);
			log.debug("Set task to {}", commandExecuted.getArguments()[0]);
		}
	}

	@VisibleForTesting
	int getIntProfileConfig(String key)
	{
		Integer value = configManager.getRSProfileConfiguration(SlayerConfig.GROUP_NAME, key, int.class);
		return value == null ? -1 : value;
	}

	private void setProfileConfig(String key, Object value)
	{
		if (value != null)
		{
			configManager.setRSProfileConfiguration(SlayerConfig.GROUP_NAME, key, value);
		}
		else
		{
			configManager.unsetRSProfileConfiguration(SlayerConfig.GROUP_NAME, key);
		}
	}

	private void save()
	{
		setProfileConfig(SlayerConfig.AMOUNT_KEY, amount);
		setProfileConfig(SlayerConfig.INIT_AMOUNT_KEY, initialAmount);
		setProfileConfig(SlayerConfig.TASK_NAME_KEY, taskName);
		setProfileConfig(SlayerConfig.TASK_LOC_KEY, taskLocation);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned npcSpawned)
	{
		NPC npc = npcSpawned.getNpc();
		if (isTarget(npc))
		{
			targets.add(npc);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned npcDespawned)
	{
		NPC npc = npcDespawned.getNpc();
		targets.remove(npc);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		int varpId = varbitChanged.getVarpId();
		int varbitId = varbitChanged.getVarbitId();
		if (varpId == VarPlayer.SLAYER_TASK_SIZE.getId()
			|| varpId == VarPlayer.SLAYER_TASK_LOCATION.getId()
			|| varpId == VarPlayer.SLAYER_TASK_CREATURE.getId())
		{
			clientThread.invokeLater(this::updateTask);
		}
		else if (varbitId == Varbits.SLAYER_POINTS)
		{
			setProfileConfig(SlayerConfig.POINTS_KEY, varbitChanged.getValue());

			// points is on a tooltip on the counter, so requires a rebuild if it changes
			if (counter != null)
			{
				removeCounter();
				addCounter();
			}
		}
		else if (varbitId == Varbits.SLAYER_TASK_STREAK)
		{
			setProfileConfig(SlayerConfig.STREAK_KEY, varbitChanged.getValue());

			// streak is on a tooltip on the counter, so requires a rebuild if it changes
			if (counter != null)
			{
				removeCounter();
				addCounter();
			}
		}
	}

	private void updateTask()
	{
		int amount = client.getVarpValue(VarPlayer.SLAYER_TASK_SIZE);
		if (amount > 0)
		{
			int taskId = client.getVarpValue(VarPlayer.SLAYER_TASK_CREATURE);
			String taskName;
			if (taskId == 98 /* Bosses, from [proc,helper_slayer_current_assignment] */)
			{
				taskName = client.getEnum(EnumID.SLAYER_TASK_BOSS)
					.getStringValue(client.getVarbitValue(Varbits.SLAYER_TASK_BOSS));
			}
			else
			{
				taskName = client.getEnum(EnumID.SLAYER_TASK_CREATURE)
					.getStringValue(taskId);
			}

			int areaId = client.getVarpValue(VarPlayer.SLAYER_TASK_LOCATION);
			String taskLocation = null;
			if (areaId > 0)
			{
				taskLocation = client.getEnum(EnumID.SLAYER_TASK_LOCATION)
					.getStringValue(areaId);
			}

			if (loginFlag)
			{
				log.debug("Sync slayer task: {}x {} at {}", amount, taskName, taskLocation);

				// initial amount is not in a var, so we initialize it from the stored amount
				initialAmount = getIntProfileConfig(SlayerConfig.INIT_AMOUNT_KEY);
				setTask(taskName, amount, initialAmount, taskLocation, false);

				// initialize streak and points in the event the plugin was toggled on after login
				setProfileConfig(SlayerConfig.POINTS_KEY, client.getVarbitValue(Varbits.SLAYER_POINTS));
				setProfileConfig(SlayerConfig.STREAK_KEY, client.getVarbitValue(Varbits.SLAYER_TASK_STREAK));
			}
			else if (!Objects.equals(taskName, this.taskName) || !Objects.equals(taskLocation, this.taskLocation))
			{
				log.debug("Task change: {}x {} at {}", amount, taskName, taskLocation);
				setTask(taskName, amount, initialAmount, taskLocation, true);
			}
			else if (amount != this.amount)
			{
				log.debug("Amount change: {} -> {}", this.amount, amount);

				this.amount = amount;
				// save changed value
				setProfileConfig(SlayerConfig.AMOUNT_KEY, amount);

				if (config.showInfobox())
				{
					// add and update counter, set timer
					addCounter();
					counter.setCount(amount);
					infoTimer = Instant.now();
				}
			}
		}
		else if (this.amount > 0)
		{
			log.debug("Task complete");
			setTask("", 0, 0);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (infoTimer != null && config.statTimeout() != 0)
		{
			Duration timeSinceInfobox = Duration.between(infoTimer, Instant.now());
			Duration statTimeout = Duration.ofMinutes(config.statTimeout());

			if (timeSinceInfobox.compareTo(statTimeout) >= 0)
			{
				removeCounter();
			}
		}

		loginFlag = false;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String chatMsg = Text.removeTags(event.getMessage()); //remove color and linebreaks

		if (chatMsg.equals(CHAT_SUPERIOR_MESSAGE) && config.showSuperiorNotification())
		{
			notifier.notify(CHAT_SUPERIOR_MESSAGE);
		}
	}

	@Subscribe
	private void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals(SlayerConfig.GROUP_NAME))
		{
			return;
		}

		if (event.getKey().equals("infobox"))
		{
			if (config.showInfobox())
			{
				clientThread.invoke(this::addCounter);
			}
			else
			{
				removeCounter();
			}
		}
		else
		{
			npcOverlayService.rebuild();
		}
	}

	@VisibleForTesting
	boolean isTarget(NPC npc)
	{
		if (targetNames.isEmpty())
		{
			return false;
		}

		final NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			return false;
		}

		final String name = composition.getName()
			.replace('\u00A0', ' ')
			.toLowerCase();

		for (Pattern target : targetNames)
		{
			final Matcher targetMatcher = target.matcher(name);
			if (targetMatcher.find()
				&& (ArrayUtils.contains(composition.getActions(), "Attack")
					// Pick action is for zygomite-fungi
					|| ArrayUtils.contains(composition.getActions(), "Pick")))
			{
				return true;
			}
		}
		return false;
	}

	private void rebuildTargetNames(Task task)
	{
		targetNames.clear();

		if (task != null)
		{
			Arrays.stream(task.getTargetNames())
				.map(SlayerPlugin::targetNamePattern)
				.forEach(targetNames::add);

			targetNames.add(targetNamePattern(taskName.replaceAll("s$", "")));
		}
	}

	private static Pattern targetNamePattern(final String targetName)
	{
		return Pattern.compile("(?:\\s|^)" + targetName + "(?:\\s|$)", Pattern.CASE_INSENSITIVE);
	}

	private void rebuildTargetList()
	{
		targets.clear();

		for (NPC npc : client.getNpcs())
		{
			if (isTarget(npc))
			{
				targets.add(npc);
			}
		}
	}

	@VisibleForTesting
	void setTask(String name, int amt, int initAmt)
	{
		setTask(name, amt, initAmt, null, true);
	}

	private void setTask(String name, int amt, int initAmt, String location, boolean addCounter)
	{
		taskName = name;
		amount = amt;
		initialAmount = Math.max(amt, initAmt);
		taskLocation = location;
		save();
		removeCounter();

		if (addCounter)
		{
			infoTimer = Instant.now();
			addCounter();
		}

		Task task = Task.getTask(name);
		rebuildTargetNames(task);
		rebuildTargetList();
		npcOverlayService.rebuild();
	}

	private void addCounter()
	{
		if (!config.showInfobox() || counter != null || Strings.isNullOrEmpty(taskName))
		{
			return;
		}

		Task task = Task.getTask(taskName);
		int itemSpriteId = ItemID.ENCHANTED_GEM;
		if (task != null)
		{
			itemSpriteId = task.getItemSpriteId();
		}

		BufferedImage taskImg = itemManager.getImage(itemSpriteId);
		String taskTooltip = ColorUtil.wrapWithColorTag("%s", new Color(255, 119, 0)) + "</br>";

		if (taskLocation != null && !taskLocation.isEmpty())
		{
			taskTooltip += taskLocation + "</br>";
		}

		taskTooltip += ColorUtil.wrapWithColorTag("Pts:", Color.YELLOW)
			+ " %s</br>"
			+ ColorUtil.wrapWithColorTag("Streak:", Color.YELLOW)
			+ " %s";

		if (initialAmount > 0)
		{
			taskTooltip += "</br>"
				+ ColorUtil.wrapWithColorTag("Start:", Color.YELLOW)
				+ " " + initialAmount;
		}

		counter = new TaskCounter(taskImg, this, amount);
		counter.setTooltip(String.format(taskTooltip, capsString(taskName), getIntProfileConfig(SlayerConfig.POINTS_KEY), getIntProfileConfig(SlayerConfig.STREAK_KEY)));

		infoBoxManager.addInfoBox(counter);
	}

	private void removeCounter()
	{
		if (counter == null)
		{
			return;
		}

		infoBoxManager.removeInfoBox(counter);
		counter = null;
	}

	void taskLookup(ChatMessage chatMessage, String message)
	{
		if (!config.taskCommand())
		{
			return;
		}

		ChatMessageType type = chatMessage.getType();

		final String player;
		if (type.equals(ChatMessageType.PRIVATECHATOUT))
		{
			player = client.getLocalPlayer().getName();
		}
		else
		{
			player = Text.removeTags(chatMessage.getName())
				.replace('\u00A0', ' ');
		}

		net.runelite.http.api.chat.Task task;
		try
		{
			task = chatClient.getTask(player);
		}
		catch (IOException ex)
		{
			log.debug("unable to lookup slayer task", ex);
			return;
		}

		if (TASK_STRING_VALIDATION.matcher(task.getTask()).find() || task.getTask().length() > TASK_STRING_MAX_LENGTH ||
			TASK_STRING_VALIDATION.matcher(task.getLocation()).find() || task.getLocation().length() > TASK_STRING_MAX_LENGTH ||
			Task.getTask(task.getTask()) == null || !isValidLocation(task.getLocation()))
		{
			log.debug("Validation failed for task name or location: {}", task);
			return;
		}

		int killed = task.getInitialAmount() - task.getAmount();

		StringBuilder sb = new StringBuilder();
		sb.append(task.getTask());
		if (!Strings.isNullOrEmpty(task.getLocation()))
		{
			sb.append(" (").append(task.getLocation()).append(')');
		}
		sb.append(": ");
		if (killed < 0)
		{
			sb.append(task.getAmount()).append(" left");
		}
		else
		{
			sb.append(killed).append('/').append(task.getInitialAmount()).append(" killed");
		}

		String response = new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("Slayer Task: ")
			.append(ChatColorType.HIGHLIGHT)
			.append(sb.toString())
			.build();

		final MessageNode messageNode = chatMessage.getMessageNode();
		messageNode.setRuneLiteFormatMessage(response);
		client.refreshChat();
	}

	private boolean taskSubmit(ChatInput chatInput, String value)
	{
		if (Strings.isNullOrEmpty(taskName))
		{
			return false;
		}

		final String playerName = client.getLocalPlayer().getName();

		executor.execute(() ->
		{
			try
			{
				chatClient.submitTask(playerName, capsString(taskName), amount, initialAmount, taskLocation);
			}
			catch (Exception ex)
			{
				log.warn("unable to submit slayer task", ex);
			}
			finally
			{
				chatInput.resume();
			}
		});

		return true;
	}

	private boolean isValidLocation(String location)
	{
		if (location == null || location.isEmpty())
		{
			return true; // no location is a valid location
		}

		if (taskLocations != null)
		{
			for (String l : taskLocations)
			{
				if (l.equalsIgnoreCase(location))
				{
					return true;
				}
			}
		}

		return false;
	}

	//Utils
	private static String capsString(String str)
	{
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}
}
