import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.awt.Color;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Hotcold extends ListenerAdapter {

    private static final Map<String, String> FLOWERS = Map.of(
            "red", "<:red:1496424239450820608>",
            "orange", "<:orange:1496424381537194175>",
            "yellow", "<:yellow:1496424271403290806>",
            "blue", "<:blue:1496424324066971668>",
            "assorted", "<:assorted:1496424438953017375>",
            "purple", "<:purple:1496424408737251328>",
            "rainbow", "<:mixed:1496424200288862260>"
    );

    private String hostId = null;
    private Message lastBettingMessage = null;
    private boolean isBettingOpen = false;
    private boolean isProcessing = false;
    private boolean isTimerStarted = false;
    private final List<Bet> currentBets = Collections.synchronizedList(new ArrayList<>());
    private final LinkedList<String> streak = new LinkedList<>();

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("hc")) {
            hostId = event.getUser().getId();
            event.reply("HotCold Session Started!").setEphemeral(true).queue();
            startNewRound(event.getOption("channel").getAsChannel().asTextChannel());
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("hc_bet_")) {
            if (!isBettingOpen) { event.reply("Round ended!").setEphemeral(true).queue(); return; }
            
            String userId = event.getUser().getId();
            synchronized (currentBets) {
                for (Bet b : currentBets) {
                    if (b.userId.equals(userId)) {
                        event.reply("❌ You have already placed a bet in this round! You can only bet on **one** option.").setEphemeral(true).queue();
                        return;
                    }
                }
            }

            event.replyModal(Modal.create("m_hc_" + id.split("_")[2], "Place Bet")
                    .addActionRows(ActionRow.of(TextInput.create("amt", "Amount (M)", TextInputStyle.SHORT).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().startsWith("m_hc_")) {
            try {
                String side = event.getModalId().split("_")[2];
                // Sanitizing input to handle 'm' suffix
                String input = event.getValue("amt").getAsString().toLowerCase().replace("m", "").trim();
                double amt = Double.parseDouble(input);
                String userId = event.getUser().getId();

                synchronized (currentBets) {
                    for (Bet b : currentBets) {
                        if (b.userId.equals(userId)) {
                            event.reply("❌ You already have an active bet!").setEphemeral(true).queue();
                            return;
                        }
                    }

                    Main.UserData ud = Main.getUserData(userId);
                    if (ud.balance < amt) { event.reply("Error: Insufficient Balance.").setEphemeral(true).queue(); return; }

                    ud.balance -= amt;
                    Main.saveUserData(userId, ud);
                    Main.updateWagerAndRakeback(userId, amt);

                    currentBets.add(new Bet(userId, side, amt));
                }

                event.reply(String.format("✅ **Bet Placed!** Amount: `%.2fM` | Side: `%s`", amt, side.toUpperCase()))
                        .setEphemeral(true)
                        .queue(hook -> hook.deleteOriginal().queueAfter(5, TimeUnit.SECONDS));

                if (!isTimerStarted) startCountdown(event.getChannel().asTextChannel());

            } catch (Exception e) { event.reply("Error: Invalid Amount.").setEphemeral(true).queue(); }
        }
    }

    private void startNewRound(TextChannel channel) {
        isBettingOpen = true; isProcessing = false; isTimerStarted = false;
        currentBets.clear();
        sendBettingEmbed(channel, false);
    }

    private void startCountdown(TextChannel channel) {
        isTimerStarted = true;
        sendBettingEmbed(channel, true);
        Main.scheduler.schedule(() -> {
            if (isBettingOpen) { isBettingOpen = false; processResults(channel); }
        }, 30, TimeUnit.SECONDS);
    }

    private void processResults(TextChannel channel) {
        if (isProcessing) return;
        isProcessing = true;

        String rolled;
        Random r = new Random();
        int chance = r.nextInt(100);

        if (chance < 3) {
            rolled = "rainbow";
        } else {
            String[] hotColdFlowers = {"red", "orange", "yellow", "blue", "assorted", "purple"};
            // FIXED: .length instead of .size()
            rolled = hotColdFlowers[r.nextInt(hotColdFlowers.length)];
        }

        String winningSide = determineSide(rolled);

        synchronized (streak) {
            if (streak.size() >= 5) streak.removeFirst();
            streak.add(FLOWERS.get(rolled));
        }

        StringBuilder payoutList = new StringBuilder("**▸ Payouts:**\n");
        StringBuilder loserList = new StringBuilder("**▸ Losers:**\n");
        boolean anybodyWon = false;
        boolean anybodyLost = false;

        List<Bet> betsToProcess;
        synchronized (currentBets) {
            betsToProcess = new ArrayList<>(currentBets);
        }

        for (Bet b : betsToProcess) {
            Main.UserData ud = Main.getUserData(b.userId);

            if (b.side.equals(winningSide)) {
                // Using switch for clean multiplier logic
                double mult = switch (winningSide) {
                    case "hot" -> 2.0;
                    case "cold" -> 2.1;
                    case "rainbow" -> 12.0;
                    default -> 0.0;
                };
                
                double winAmt = b.amount * mult;
                ud.balance += winAmt;
                Main.saveUserData(b.userId, ud);
                
                payoutList.append("<@").append(b.userId).append("> WON **").append(String.format("%.2f", winAmt)).append("M**\n");
                anybodyWon = true;
            } else {
                loserList.append("<@").append(b.userId).append("> LOST **").append(String.format("%.2f", b.amount)).append("M**\n");
                anybodyLost = true;
            }
        }

        EmbedBuilder rb = new EmbedBuilder()
                .setAuthor("Hot Cold Result", null, channel.getGuild().getIconUrl())
                .setColor(anybodyWon ? Color.GREEN : Color.RED)
                .setDescription(String.format(
                        "**Rolled Flower:** %s (%s FLOWERS)\n\n" +
                                "# %s WINS!",
                        FLOWERS.get(rolled), rolled.toUpperCase(), winningSide.toUpperCase()
                ));

        if (anybodyWon) rb.addField("", payoutList.toString(), false);
        if (anybodyLost) rb.addField("", loserList.toString(), false);
        if (!anybodyWon && !anybodyLost) rb.addField("", "*No real players in this round.*", false);

        rb.setTimestamp(Instant.now());
        if (lastBettingMessage != null) lastBettingMessage.delete().queue(null, t -> {});

        channel.sendMessageEmbeds(rb.build()).queue(s ->
                Main.scheduler.schedule(() -> startNewRound(channel), 5, TimeUnit.SECONDS)
        );
    }

    private void sendBettingEmbed(TextChannel channel, boolean withTimer) {
        String streakStr;
        synchronized (streak) {
            streakStr = streak.isEmpty() ? "None" : String.join(" ", streak);
        }

        String timeDisplay = withTimer ? String.format("<t:%d:R>", (System.currentTimeMillis() / 1000) + 30) : "Waiting for bets...";
        EmbedBuilder eb = new EmbedBuilder()
                .setAuthor("Play Hot Cold", null, channel.getGuild().getIconUrl())
                .setColor(new Color(255, 105, 180))
                .setDescription(String.format(
                        "**▸ Next Game Time:** %s\n" +
                                "**▸ Payout Multiplier (Hot):** `x2.0`\n" +
                                "**▸ Payout Multiplier (Cold):** `x2.1`\n" +
                                "**▸ Payout Multiplier (Rainbow):** `x12.0`\n" +
                                "**▸ Current Streak:** %s\n\n" +
                                "Click a button bellow to bet on Hot, Cold or Rainbow! Games are automatically launched every 30 seconds",
                        timeDisplay, streakStr
                ))
                .setFooter("Hosted by Staff")
                .setTimestamp(Instant.now());

        if (withTimer && lastBettingMessage != null) {
            lastBettingMessage.editMessageEmbeds(eb.build()).queue(null, t -> {
                // If edit fails, send a new one
                createNewBettingMessage(channel, eb);
            });
        } else {
            createNewBettingMessage(channel, eb);
        }
    }

    private void createNewBettingMessage(TextChannel channel, EmbedBuilder eb) {
        channel.sendMessageEmbeds(eb.build()).addActionRow(
                Button.danger("hc_bet_hot", "Bet on Hot"),
                Button.primary("hc_bet_cold", "Bet on Cold"),
                Button.success("hc_bet_rainbow", "Bet on Rainbow")
        ).queue(msg -> lastBettingMessage = msg);
    }

    private String determineSide(String flower) {
        if (flower.equals("red") || flower.equals("orange") || flower.equals("yellow")) return "hot";
        if (flower.equals("blue") || flower.equals("assorted") || flower.equals("purple")) return "cold";
        return "rainbow";
    }

    private static class Bet { String userId, side; double amount; Bet(String u, String s, double a) { userId=u; side=s; amount=a; } }
}
