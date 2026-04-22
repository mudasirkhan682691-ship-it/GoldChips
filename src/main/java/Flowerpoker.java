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

public class Flowerpoker extends ListenerAdapter {

    private static final Map<Integer, String> FLOWERS = Map.of(
            1,"<:mixed:1496424200288862260>", 2,"<:assorted:1496424438953017375>",
            3,"<:blue:1496424324066971668>", 4,"<:orange:1496424381537194175>",
            5,"<:purple:1496424408737251328>", 6,"<:red:1496424239450820608>",
            7,"<:yellow:1496424271403290806>");

    private String hostId = null;
    private Message lastBettingMessage = null;
    private boolean isBettingOpen = false;
    private boolean isProcessing = false;
    private boolean isTimerStarted = false;
    private final List<Bet> currentBets = new ArrayList<>();
    private final LinkedList<String> streak = new LinkedList<>();

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("fp")) {
            hostId = event.getUser().getId();
            event.reply("Session Started!").setEphemeral(true).queue();
            startNewRound(event.getOption("channel").getAsChannel().asTextChannel());
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("bet_")) {
            if (!isBettingOpen) { event.reply("Round ended!").setEphemeral(true).queue(); return; }
            event.replyModal(Modal.create("m_bet_" + id.split("_")[1], "Place Bet")
                    .addActionRows(ActionRow.of(TextInput.create("amt", "Amount (M)", TextInputStyle.SHORT).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().startsWith("m_bet_")) {
            try {
                String sideRaw = event.getModalId().split("_")[2];
                double amt = Double.parseDouble(event.getValue("amt").getAsString().toLowerCase().replace("m", ""));
                String userId = event.getUser().getId();

                for (Bet b : currentBets) {
                    if (!b.isFake && b.userId.equals(userId)) {
                        if ((sideRaw.equals("player") && b.side.equals("house")) ||
                                (sideRaw.equals("house") && b.side.equals("player"))) {
                            event.reply("❌ You cannot bet on both **Player** and **House** in the same round!").setEphemeral(true).queue();
                            return;
                        }
                    }
                }

                // Integration with MongoDB via Main.java
                Main.UserData ud = Main.getUserData(userId);
                if (ud.balance < amt) { event.reply("Error: Insufficient Balance.").setEphemeral(true).queue(); return; }

                // Update Balance and Cloud Save
                ud.balance -= amt;
                Main.saveUserData(userId, ud); 
                Main.updateWagerAndRakeback(userId, amt);

                currentBets.add(new Bet(userId, sideRaw, amt, false));

                String sideDisplay = sideRaw.substring(0, 1).toUpperCase() + sideRaw.substring(1);
                event.reply(String.format("✅ **Bet Placed!** Amount: `%.2fM` | Side: `%s`", amt, sideDisplay))
                        .setEphemeral(true)
                        .queue(hook -> hook.deleteOriginal().queueAfter(5, TimeUnit.SECONDS));

                if (!isTimerStarted) startCountdown(event.getChannel().asTextChannel());

            } catch (Exception e) { event.reply("Error: Invalid Amount.").setEphemeral(true).queue(); }
        }
    }

    private void startNewRound(TextChannel channel) {
        isBettingOpen = true; isProcessing = false; isTimerStarted = false;
        currentBets.clear();
        Random r = new Random();
        currentBets.add(new Bet(genId(), "tie", 1 + r.nextInt(50), true));
        currentBets.add(new Bet(genId(), "player", 1 + r.nextInt(50), true));
        currentBets.add(new Bet(genId(), "house", 1 + r.nextInt(50), true));
        sendBettingEmbed(channel, false);
    }

    private void startCountdown(TextChannel channel) {
        isTimerStarted = true;
        sendBettingEmbed(channel, true);
        Main.scheduler.schedule(() -> {
            if (isBettingOpen) { isBettingOpen = false; processResults(channel); }
        }, 45, TimeUnit.SECONDS);
    }

    private void processResults(TextChannel channel) {
        if (isProcessing) return;
        isProcessing = true;
        List<Integer> pHand = genHand(); List<Integer> hHand = genHand();
        HandRank pRank = eval(pHand); HandRank hRank = eval(hHand);
        String winner = pRank.score > hRank.score ? "player" : (hRank.score > pRank.score ? "house" : "tie");

        if (streak.size() >= 5) streak.removeFirst();
        streak.add(winner.substring(0, 1).toUpperCase() + winner.substring(1));

        StringBuilder payoutList = new StringBuilder("**▸ Payouts:**\n");
        StringBuilder loserList = new StringBuilder("**▸ Losers:**\n");
        boolean anybodyWonRealMoney = false;
        boolean anybodyLost = false;

        Map<String, List<Bet>> userBets = currentBets.stream()
                .filter(b -> !b.isFake)
                .collect(Collectors.groupingBy(b -> b.userId));

        for (Map.Entry<String, List<Bet>> entry : userBets.entrySet()) {
            String uId = entry.getKey();
            List<Bet> bets = entry.getValue();
            Main.UserData ud = Main.getUserData(uId);

            boolean hasTieBet = bets.stream().anyMatch(b -> b.side.equals("tie"));
            boolean userProcessed = false;
            double totalLostAmt = 0;

            for (Bet b : bets) {
                if (winner.equals("tie")) {
                    if (b.side.equals("tie")) {
                        double winAmt = b.amount * 2.8;
                        ud.balance += winAmt;
                        payoutList.append("<@").append(uId).append("> WON **").append(String.format("%.2f", winAmt)).append("M**\n");
                        anybodyWonRealMoney = true; userProcessed = true;
                    } else if (!hasTieBet) {
                        ud.balance += b.amount;
                        payoutList.append("<@").append(uId).append("> REFUNDED **").append(String.format("%.2f", b.amount)).append("M**\n");
                        userProcessed = true;
                    } else { totalLostAmt += b.amount; }
                }
                else if (b.side.equals(winner)) {
                    double winAmt = b.amount * 1.9;
                    ud.balance += winAmt;
                    payoutList.append("<@").append(uId).append("> WON **").append(String.format("%.2f", winAmt)).append("M**\n");
                    anybodyWonRealMoney = true; userProcessed = true;
                } else {
                    totalLostAmt += b.amount;
                }
            }
            
            Main.saveUserData(uId, ud); // Save result to MongoDB

            if (!userProcessed || totalLostAmt > 0) {
                if (totalLostAmt > 0) {
                    loserList.append("<@").append(uId).append("> LOST **").append(String.format("%.2f", totalLostAmt)).append("M**\n");
                    anybodyLost = true;
                }
            }
        }

        Color sidebarColor = anybodyWonRealMoney ? Color.GREEN : (winner.equals("tie") ? Color.WHITE : Color.RED);

        EmbedBuilder rb = new EmbedBuilder()
                .setAuthor("Flower Poker Result", null, channel.getGuild().getIconUrl())
                .setColor(sidebarColor)
                .setDescription(String.format(
                        "**Player:** %s (%s)\n" +
                                "**House:** %s (%s)\n\n" +
                                "**Result:** %s vs %s\n" +
                                "# %s WINS!",
                        format(pHand), pRank.name, format(hHand), hRank.name, pRank.name, hRank.name, winner.toUpperCase()
                ));

        if (anybodyWonRealMoney) rb.addField("", payoutList.toString(), false);
        if (anybodyLost) rb.addField("", loserList.toString(), false);
        if (!anybodyWonRealMoney && !anybodyLost) rb.addField("", "*No real players in this round.*", false);

        rb.setTimestamp(Instant.now());
        if (lastBettingMessage != null) lastBettingMessage.delete().queue(null, t -> {});
        channel.sendMessageEmbeds(rb.build()).queue(s -> Main.scheduler.schedule(() -> startNewRound(channel), 5, TimeUnit.SECONDS));
    }

    private void sendBettingEmbed(TextChannel channel, boolean withTimer) {
        String timeDisplay = withTimer ? String.format("<t:%d:R>", (System.currentTimeMillis() / 1000) + 45) : "Waiting for bets...";
        EmbedBuilder eb = new EmbedBuilder()
                .setAuthor("Play Flower Poker", null, channel.getGuild().getIconUrl())
                .setColor(new Color(255, 105, 180))
                .setDescription(String.format(
                        "**▸ Next Game Time:** %s\n" +
                                "**▸ Payout Multiplier (Player/House):** `x1.9`\n" +
                                "**▸ Payout Multiplier (Tie):** `x2.8`\n" +
                                "**▸ Current Streak:** %s\n\n" +
                                "Click a button bellow to bet on **Player**, **House** or **Tie**! Games are automatically launched every 45 seconds",
                        timeDisplay, (streak.isEmpty() ? "None" : String.join(", ", streak))
                ))
                .setFooter("Hosted by Staff")
                .setTimestamp(Instant.now());

        if (withTimer && lastBettingMessage != null) lastBettingMessage.editMessageEmbeds(eb.build()).queue();
        else channel.sendMessageEmbeds(eb.build()).addActionRow(
                Button.success("bet_player", "Bet on Player"),
                Button.danger("bet_house", "Bet on House"),
                Button.primary("bet_tie", "Bet on Tie")
        ).queue(msg -> lastBettingMessage = msg);
    }

    private List<Integer> genHand() { return new Random().ints(5, 1, 8).boxed().collect(Collectors.toList()); }
    private String format(List<Integer> h) { return h.stream().map(FLOWERS::get).collect(Collectors.joining(" ")); }
    private String genId() { return (new Random().nextInt(8) + 1) + "0000000000000000"; }

    private HandRank eval(List<Integer> h) {
        Map<Integer, Long> c = h.stream().collect(Collectors.groupingBy(i -> i, Collectors.counting()));
        List<Long> v = c.values().stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        if (v.get(0) == 5) return new HandRank(6, "5 OAK");
        if (v.get(0) == 4) return new HandRank(5, "4 OAK");
        if (v.get(0) == 3 && v.get(1) == 2) return new HandRank(4, "Full House");
        if (v.get(0) == 3) return new HandRank(3, "3 OAK");
        if (v.get(0) == 2 && v.get(1) == 2) return new HandRank(2, "2 Pair");
        if (v.get(0) == 2) return new HandRank(1, "1 Pair");
        return new HandRank(0, "Bust");
    }

    private static class Bet { String userId, side; double amount; boolean isFake; Bet(String u, String s, double a, boolean f) { userId=u; side=s; amount=a; isFake=f; } }
    private static class HandRank { int score; String name; HandRank(int s, String n) { score=s; name=n; } }
}
