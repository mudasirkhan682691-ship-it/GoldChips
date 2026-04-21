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
import java.util.stream.Collectors;

public class Hotcold extends ListenerAdapter {

    private static final Map<String, String> FLOWERS = Map.of(
            "red", "<:red:1495282842329022494>",
            "orange", "<:orange:1495282953507311646>",
            "yellow", "<:yellow:1495282879704469594>",
            "blue", "<:blue:1495282922809327827>",
            "assorted", "<:assorted:1495283043299233802>",
            "purple", "<:purple:1495283008251494410>",
            "rainbow", "<:mixed:1495282804534153296>"
    );

    private String hostId = null;
    private Message lastBettingMessage = null;
    private boolean isBettingOpen = false;
    private boolean isProcessing = false;
    private boolean isTimerStarted = false;
    private final List<Bet> currentBets = new ArrayList<>();
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
            event.replyModal(Modal.create("m_hc_" + id.split("_")[2], "Place Bet")
                    .addActionRows(ActionRow.of(TextInput.create("amt", "Amount (M)", TextInputStyle.SHORT).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().startsWith("m_hc_")) {
            try {
                String side = event.getModalId().split("_")[2];
                double amt = Double.parseDouble(event.getValue("amt").getAsString().toLowerCase().replace("m", ""));
                String userId = event.getUser().getId();

                Main.UserData ud = Main.database.computeIfAbsent(userId, k -> new Main.UserData());
                if (ud.balance < amt) { event.reply("Error: Insufficient Balance.").setEphemeral(true).queue(); return; }

                ud.balance -= amt;
                ud.wagered += amt;
                ud.rakeback += (amt * 0.006);

                currentBets.add(new Bet(userId, side, amt));
                Main.saveData();

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

        String[] allPossible = {"red", "orange", "yellow", "blue", "assorted", "purple", "rainbow"};
        String rolled = allPossible[new Random().nextInt(allPossible.length)];
        String winningSide = determineSide(rolled);

        if (streak.size() >= 5) streak.removeFirst();
        streak.add(FLOWERS.get(rolled));

        StringBuilder payoutList = new StringBuilder("**▸ Payouts:**\n");
        StringBuilder loserList = new StringBuilder("**▸ Losers:**\n");
        boolean anybodyWon = false;
        boolean anybodyLost = false;
        boolean updateNeeded = false;

        for (Bet b : currentBets) {
            Main.UserData ud = Main.database.get(b.userId);
            if (ud == null) continue;

            if (b.side.equals(winningSide)) {
                double mult = winningSide.equals("hot") ? 2.5 : (winningSide.equals("cold") ? 3.0 : 8.0);
                double winAmt = b.amount * mult;
                ud.balance += winAmt;
                payoutList.append("<@").append(b.userId).append("> WON **").append(String.format("%.2f", winAmt)).append("M**\n");
                anybodyWon = true; updateNeeded = true;
            } else {
                loserList.append("<@").append(b.userId).append("> LOST **").append(String.format("%.2f", b.amount)).append("M**\n");
                anybodyLost = true;
            }
        }

        if (updateNeeded) Main.saveData();

        Color sidebarColor = anybodyWon ? Color.GREEN : Color.RED;

        EmbedBuilder rb = new EmbedBuilder()
                .setAuthor("Hot Cold Result", null, channel.getGuild().getIconUrl())
                .setColor(sidebarColor)
                .setDescription(String.format(
                        "**Rolled Flower:** %s (%s)\n\n" +
                                "# %s WINS!",
                        FLOWERS.get(rolled), rolled.toUpperCase(), winningSide.toUpperCase()
                ));

        if (updateNeeded) rb.addField("", payoutList.toString(), false);
        if (anybodyLost) rb.addField("", loserList.toString(), false);
        if (!anybodyWon && !anybodyLost) rb.addField("", "*No real players in this round.*", false);

        rb.setTimestamp(Instant.now());
        if (lastBettingMessage != null) lastBettingMessage.delete().queue(null, t -> {});

        channel.sendMessageEmbeds(rb.build()).queue(s ->
                Main.scheduler.schedule(() -> startNewRound(channel), 5, TimeUnit.SECONDS)
        );
    }

    private void sendBettingEmbed(TextChannel channel, boolean withTimer) {
        String timeDisplay = withTimer ? String.format("<t:%d:R>", (System.currentTimeMillis() / 1000) + 30) : "Waiting for bets...";
        EmbedBuilder eb = new EmbedBuilder()
                .setAuthor("Play Hot Cold", null, channel.getGuild().getIconUrl())
                .setColor(new Color(255, 105, 180))
                .setDescription(String.format(
                        "**▸ Next Game Time:** %s\n" +
                                "**▸ Payout Multiplier (Hot):** `x2.5`\n" +
                                "**▸ Payout Multiplier (Cold):** `x3.0`\n" +
                                "**▸ Payout Multiplier (Rainbow):** `x8.0`\n" +
                                "**▸ Current Streak:** %s\n\n" +
                                "Click a button bellow to bet on Hot, Cold or Rainbow! Games are automatically launched every 30 seconds",
                        timeDisplay, (streak.isEmpty() ? "None" : String.join(" ", streak))
                ))
                .setFooter("Hosted by Staff")
                .setTimestamp(Instant.now());

        if (withTimer && lastBettingMessage != null) {
            lastBettingMessage.editMessageEmbeds(eb.build()).queue();
        } else {
            channel.sendMessageEmbeds(eb.build()).addActionRow(
                    Button.danger("hc_bet_hot", "Bet on Hot"),
                    Button.primary("hc_bet_cold", "Bet on Cold"),
                    Button.success("hc_bet_rainbow", "Bet on Rainbow")
            ).queue(msg -> lastBettingMessage = msg);
        }
    }

    private String determineSide(String flower) {
        if (flower.equals("red") || flower.equals("orange") || flower.equals("yellow")) return "hot";
        if (flower.equals("blue") || flower.equals("assorted") || flower.equals("purple")) return "cold";
        return "rainbow";
    }

    private static class Bet { String userId, side; double amount; Bet(String u, String s, double a) { userId=u; side=s; amount=a; } }
}