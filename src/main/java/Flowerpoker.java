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

    private static final String[] FLOWERS = {
        "<:red:1496424239450820608>", "<:orange:1496424381537194175>", 
        "<:yellow:1496424271403290806>", "<:blue:1496424324066971668>", 
        "<:assorted:1496424438953017375>", "<:purple:1496424408737251328>", 
        "<:mixed:1496424200288862260>"
    };

    private final List<Bet> activeBets = Collections.synchronizedList(new ArrayList<>());
    private boolean isAcceptingBets = false;
    private Message gameMessage = null;

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("fp")) {
            TextChannel channel = event.getOption("channel").getAsChannel().asTextChannel();
            startNewGame(channel);
            event.reply("Flower Poker Started!").setEphemeral(true).queue();
        }
    }

    private void startNewGame(TextChannel channel) {
        activeBets.clear();
        isAcceptingBets = true;
        
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🌸 Flower Poker | Open for Bets")
                .setDescription("Click the button below to join the game!\n**Multiplier:** `x1.95`\n\n**Time Remaining:** <t:" + ((System.currentTimeMillis() / 1000) + 30) + ":R>")
                .setColor(Color.MAGENTA)
                .setFooter("Hosted by Staff")
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(eb.build())
                .addActionRow(Button.success("fp_join", "Join Game"))
                .queue(msg -> {
                    gameMessage = msg;
                    Main.scheduler.schedule(() -> runGame(channel), 30, TimeUnit.SECONDS);
                });
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getComponentId().equals("fp_join")) {
            if (!isAcceptingBets) {
                event.reply("❌ Betting is closed for this round!").setEphemeral(true).queue();
                return;
            }
            
            // Check if already joined
            boolean alreadyIn = activeBets.stream().anyMatch(b -> b.userId.equals(event.getUser().getId()));
            if (alreadyIn) {
                event.reply("❌ You are already in this game!").setEphemeral(true).queue();
                return;
            }

            event.replyModal(Modal.create("modal_fp_bet", "Enter Bet Amount")
                    .addActionRows(ActionRow.of(TextInput.create("amount", "Amount (M)", TextInputStyle.SHORT).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().equals("modal_fp_bet")) {
            try {
                double amt = Double.parseDouble(event.getValue("amount").getAsString().replace("m", "").trim());
                String userId = event.getUser().getId();
                Main.UserData ud = Main.getUserData(userId);

                if (ud.balance < amt) {
                    event.reply("❌ Insufficient Balance!").setEphemeral(true).queue();
                    return;
                }

                ud.balance -= amt;
                Main.saveUserData(userId, ud);
                Main.updateWagerAndRakeback(userId, amt);
                
                activeBets.add(new Bet(userId, amt));
                event.reply("✅ Bet of " + amt + "M placed!").setEphemeral(true).queue();
            } catch (Exception e) {
                event.reply("❌ Invalid Amount.").setEphemeral(true).queue();
            }
        }
    }

    private void runGame(TextChannel channel) {
        isAcceptingBets = false;
        if (activeBets.isEmpty()) {
            channel.sendMessage("Game cancelled: No bets placed.").queue();
            return;
        }

        Random r = new Random();
        List<String> hostHand = generateHand(r);
        int hostScore = calculateScore(hostHand);

        EmbedBuilder rb = new EmbedBuilder()
                .setTitle("🌸 Flower Poker Results")
                .addField("🎙️ Host Hand", String.join(" ", hostHand), false)
                .setColor(Color.CYAN);

        StringBuilder results = new StringBuilder();
        for (Bet b : activeBets) {
            List<String> userHand = generateHand(r);
            int userScore = calculateScore(userHand);
            Main.UserData ud = Main.getUserData(b.userId);

            results.append("<@").append(b.userId).append(">: ").append(String.join(" ", userHand));
            
            if (userScore > hostScore) {
                double win = b.amount * 1.95;
                ud.balance += win;
                results.append(" **(WON ").append(String.format("%.2f", win)).append("M)** ✅\n");
            } else if (userScore == hostScore) {
                ud.balance += b.amount; // Refund on Tie
                results.append(" **(TIE - Refunded)** 🤝\n");
            } else {
                results.append(" **(LOST)** ❌\n");
            }
            Main.saveUserData(b.userId, ud);
        }

        rb.addField("🎮 Players", results.toString(), false);
        rb.setTimestamp(Instant.now());
        
        if (gameMessage != null) gameMessage.delete().queue(null, e -> {});
        channel.sendMessageEmbeds(rb.build()).queue(m -> 
            Main.scheduler.schedule(() -> startNewGame(channel), 10, TimeUnit.SECONDS)
        );
    }

    private List<String> generateHand(Random r) {
        List<String> hand = new ArrayList<>();
        for (int i = 0; i < 5; i++) hand.add(FLOWERS[r.nextInt(FLOWERS.length)]);
        return hand;
    }

    private int calculateScore(List<String> hand) {
        Map<String, Long> counts = hand.stream().collect(Collectors.groupingBy(e -> e, Collectors.counting()));
        List<Long> values = counts.values().stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList());

        if (values.get(0) == 5) return 6; // 5 of a kind
        if (values.get(0) == 4) return 5; // 4 of a kind
        if (values.get(0) == 3 && values.size() > 1 && values.get(1) == 2) return 4; // Full House
        if (values.get(0) == 3) return 3; // 3 of a kind
        if (values.get(0) == 2 && values.size() > 1 && values.get(1) == 2) return 2; // 2 Pair
        if (values.get(0) == 2) return 1; // 1 Pair
        return 0; // High card
    }

    private static class Bet { 
        String userId; double amount; 
        Bet(String u, double a) { this.userId = u; this.amount = a; } 
    }
}
