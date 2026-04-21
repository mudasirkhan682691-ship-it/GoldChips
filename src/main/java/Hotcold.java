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
            "red", "<:red:1495282842329022494>",
            "orange", "<:orange:1495282953507311646>",
            "yellow", "<:yellow:1495282879704469594>",
            "blue", "<:blue:1495282922809327827>",
            "assorted", "<:assorted:1495283043299233802>",
            "purple", "<:purple:1495283008251494410>",
            "rainbow", "<:mixed:1495282804534153296>"
    );

    private Message lastBettingMessage = null;
    private boolean isBettingOpen = false;
    private boolean isProcessing = false;
    private boolean isTimerStarted = false;
    private final List<Bet> currentBets = new ArrayList<>();
    private final LinkedList<String> streak = new LinkedList<>();

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("hc")) {
            event.reply("✅ HotCold Session Started!").setEphemeral(true).queue();
            startNewRound(event.getOption("channel").getAsChannel().asTextChannel());
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("hc_bet_")) {
            if (!isBettingOpen) { 
                event.reply("❌ This round has ended! Please wait for the next one.").setEphemeral(true).queue(); 
                return; 
            }
            String side = id.split("_")[2];
            event.replyModal(Modal.create("m_hc_" + side, "Place Your Bet")
                    .addActionRows(ActionRow.of(TextInput.create("amt", "Amount (M)", TextInputStyle.SHORT)
                            .setPlaceholder("Example: 10")
                            .setRequired(true).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().startsWith("m_hc_")) {
            try {
                String side = event.getModalId().split("_")[2];
                double amt = Double.parseDouble(event.getValue("amt").getAsString().toLowerCase().replace("m", ""));
                String userId = event.getUser().getId();

                if (amt <= 0) { event.reply("❌ Amount must be greater than 0!").setEphemeral(true).queue(); return; }

                // MongoDB fetch balance
                Main.UserData ud = Main.getUserData(userId);
                if (ud.balance < amt) { 
                    event.reply("❌ Insufficient Balance! Current: " + String.format("%.2fM", ud.balance)).setEphemeral(true).queue(); 
                    return; 
                }

                // Deduct and Save to MongoDB
                ud.balance -= amt;
                Main.saveUserData(userId, ud); 
                Main.updateWagerAndRakeback(userId, amt);

                currentBets.add(new Bet(userId, side, amt));

                event.reply(String.format("✅ **Bet Placed!**\nAmount: `%.2fM` | Side: `%s`", amt, side.toUpperCase()))
                        .setEphemeral(true).queue();

                if (!isTimerStarted) startCountdown(event.getChannel().asTextChannel());

            } catch (Exception e) { 
                event.reply("❌ Invalid Amount Format! Please enter a number.").setEphemeral(true).queue(); 
            }
        }
    }

    private void startNewRound(TextChannel channel) {
        isBettingOpen = true; 
        isProcessing = false; 
        isTimerStarted = false;
        currentBets.clear();
        sendBettingEmbed(channel, false);
    }

    private void startCountdown(TextChannel channel) {
        isTimerStarted = true;
        sendBettingEmbed(channel, true);
        Main.scheduler.schedule(() -> {
            if (isBettingOpen) { 
                isBettingOpen = false; 
                processResults(channel); 
            }
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

        for (Bet b : currentBets) {
            Main.UserData ud = Main.getUserData(b.userId);

            if (b.side.equals(winningSide)) {
                double mult = winningSide.equals("hot") ? 2.5 : (winningSide.equals("cold") ? 3.0 : 8.0);
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
                        "**Rolled Flower:** %s (%s)\n\n" +
                        "### %s WINS!",
                        FLOWERS.get(rolled), rolled.toUpperCase(), winningSide.toUpperCase()
                ));

        if (anybodyWon) rb.addField("", payoutList.toString(), false);
        if (anybodyLost) rb.addField("", loserList.toString(), false);
        if (!anybodyWon && !anybodyLost) rb.addField("", "*No active players this round.*", false);

        rb.setFooter("Arcade Economy System").setTimestamp(Instant.now());
        
        if (lastBettingMessage != null) lastBettingMessage.delete().queue(null, t -> {});

        channel.sendMessageEmbeds(rb.build()).queue(s ->
                Main.scheduler.schedule(() -> startNewRound(channel), 6, TimeUnit.SECONDS)
        );
    }

    private void sendBettingEmbed(TextChannel channel, boolean withTimer) {
        String timeDisplay = withTimer ? String.format("<t:%d:R>", (System.currentTimeMillis() / 1000) + 30) : "Waiting for bets...";
        EmbedBuilder eb = new EmbedBuilder()
                .setAuthor("Play Hot Cold", null, channel.getGuild().getIconUrl())
                .setColor(new Color(255, 105, 180)) // Hot Pink
                .setDescription(String.format(
                        "**▸ Round Starts:** %s\n" +
                        "**▸ Hot (R/O/Y):** `x2.5`\n" +
                        "**▸ Cold (B/A/P):** `x3.0`\n" +
                        "**▸ Rainbow (M):** `x8.0`\n" +
                        "**▸ History:** %s\n\n" +
                        "Click a button below to place your bet!",
                        timeDisplay, (streak.isEmpty() ? "None" : String.join(" ", streak))
                ))
                .setFooter("Minimum: 0.01M | Maximum: 500M")
                .setTimestamp(Instant.now());

        if (withTimer && lastBettingMessage != null) {
            lastBettingMessage.editMessageEmbeds(eb.build()).queue();
        } else {
            channel.sendMessageEmbeds(eb.build()).addActionRow(
                    Button.danger("hc_bet_hot", "Bet Hot"),
                    Button.primary("hc_bet_cold", "Bet Cold"),
                    Button.success("hc_bet_rainbow", "Bet Rainbow")
            ).queue(msg -> lastBettingMessage = msg);
        }
    }

    private String determineSide(String flower) {
        if (flower.equals("red") || flower.equals("orange") || flower.equals("yellow")) return "hot";
        if (flower.equals("blue") || flower.equals("assorted") || flower.equals("purple")) return "cold";
        return "rainbow";
    }

    private static class Bet { 
        String userId, side; double amount; 
        Bet(String u, String s, double a) { userId=u; side=s; amount=a; } 
    }
}
