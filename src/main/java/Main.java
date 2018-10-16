package com.company;

import com.google.gson.*;
import org.apache.commons.io.IOUtils;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.TelegramBotsApi;
import org.telegram.telegrambots.api.methods.send.*;
import org.telegram.telegrambots.api.objects.Message;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Main {

    private static String BOT_NAME = "";
    private static String BOT_TOKEN = "";
    private static String TWITCH_ID = "";

    private static HashSet<Channel> channels = new HashSet<Channel>();

    public static void main(String[] args) {

        String config = System.getenv().get("CONFIG");

        try {
            parseConfig(config);
            ApiContextInitializer.init();
            TelegramBotsApi botsApi = new TelegramBotsApi();
            botsApi.registerBot(new Bot(BOT_NAME, BOT_TOKEN));
        } catch(JsonParseException e) {
            System.out.print("error: parse json exception\n");
        } catch (TelegramApiException e) {
            System.out.print("error: telegram exception\n");
        }
    }

    private static void parseConfig(String data) throws JsonParseException {
        JsonParser parser = new JsonParser();
        JsonElement jsonElement = parser.parse(data);

        if (!jsonElement.isJsonObject())
            throw new JsonParseException("is not json object");

        JsonObject jsonObject = jsonElement.getAsJsonObject();
        if (!jsonObject.has("bot_name") || !jsonObject.has("bot_token") || !jsonObject.has("twitch_id") ||
                !jsonObject.has("channels") || !jsonObject.get("channels").isJsonArray())
            throw new JsonParseException("incorrect json struct");

        BOT_NAME = jsonObject.get("bot_name").getAsString();
        BOT_TOKEN = jsonObject.get("bot_token").getAsString();
        TWITCH_ID = jsonObject.get("twitch_id").getAsString();

        JsonArray jsonChannels = jsonObject.get("channels").getAsJsonArray();
        for (JsonElement jsonChannel : jsonChannels) {
            if (!jsonChannel.isJsonObject())
                throw new JsonParseException("incorrect json struct");
            jsonObject = jsonChannel.getAsJsonObject();
            if (!jsonObject.has("link") || !jsonObject.has("chats") || !jsonObject.get("chats").isJsonArray())
                throw new JsonParseException("incorrect json struct");
            try {
                String link = jsonObject.get("link").getAsString();
                Channel channel = createChannel(link);
                JsonArray jsonChats = jsonObject.get("chats").getAsJsonArray();
                for (JsonElement jsonChat : jsonChats)
                    channel.addChatId(jsonChat.getAsLong());
                channels.add(channel);
            } catch (MalformedURLException e) {
                throw new JsonParseException("incorrect link");
            }

        }
    }

    private static Channel createChannel(String link) throws MalformedURLException {
        System.out.print(link + "\n");

        Channel channel = null;

        if (link.contains("twitch.tv")) {
            Pattern pattern = Pattern.compile("^https://twitch.tv/([A-Za-z0-9_]+)$");
            Matcher matcher  = pattern.matcher(link);
            if (matcher.matches()) {
                String name = matcher.group(1);
                channel = new TwitchChannel(name);
            }
        }

        else if (link.contains("goodgame.ru")) {
            Pattern pattern = Pattern.compile("^https://goodgame.ru/channel/([A-Za-z0-9_]+)$");
            Matcher matcher  = pattern.matcher(link);
            if (matcher.matches()) {
                String name = matcher.group(1);
                channel = new GoodGameChannel(name);
            }
        }

        if (channel == null) {
            throw new MalformedURLException("incorrect link");
        }

        return channel;
    }

    static class Bot extends TelegramLongPollingBot {
        final private String name;
        final private String token;

        final private int UPDATE_DELAY = 300;

        private StreamMonitor stremMonitorThread = new StreamMonitor();

        Bot(String name, String token) {
            this.name = name;
            this.token = token;
            stremMonitorThread.start();
        }

        @Override
        public String getBotUsername() {
            return name;
        }

        @Override
        public String getBotToken() {
            return token;
        }

        @Override
        public void onUpdateReceived(Update update) {
            if (update.hasMessage()) {
                Message message = update.getMessage();
                System.out.print("chat id: " + message.getChatId() + "\n");
                if (message.isCommand() && message.hasText()) {
                    if (message.getText().contains("/status")) {
                        Boolean existOnlineChannels = false;
                        Long chatId = message.getChatId();
                        for (Channel channel : channels) {
                            //try {
                            //    channel.update();
                            //} catch (IOException e ) {
                            //    e.printStackTrace();
                            //}
                            if (channel.isOnline() && channel.hasChatId(chatId)) {
                                existOnlineChannels = true;
                                sendChannelInfo(channel, chatId);
                            }
                        }
                        if (!existOnlineChannels) {
                            sendMessage("Нет запущенных стримов", chatId);
                        }

                    }
                }
            }
        }

        private void sendMessage(String text, Long chatId) {
            SendMessage sendMessageRequest = new SendMessage();
            sendMessageRequest.setChatId(chatId);
            sendMessageRequest.setText(text);
            sendMessageRequest.enableHtml(true);
            try {
                execute(sendMessageRequest);
            } catch (TelegramApiException e) {
                System.out.print("error: cannot send telegram msg\n");
            }
        }

        private void sendAnnouncements(Channel channel, Long chatId) {
            String text = new String();
            if (channel.isOnline()) {
                text += "<b>Начало стрима на " + channel.getPlatform() + "!</b>\n";
                text += "Трансляция: " + channel.getBroadcastTitle() + "\n";
                text += "Игра: " + channel.getGameTitle() + "\n";
                text += "Число зрителей: " + channel.getViewers() + "\n";
            }
            else {
                text += "<b>Стрим на " + channel.getPlatform() + " завершен.\n</b>";
            }
            text += channel.getLink();
            sendMessage(text, chatId);
        }

        private void sendChannelInfo(Channel channel, Long chatId) {
            String text = new String();
            if (channel.isOnline()) {
                text += "<a href = \"" + channel.getPreviewLink() + "\">Инфо:</a>" + "\n";
                text += "Трансляция: " + channel.getBroadcastTitle() + "\n";
                text += "Игра: " + channel.getGameTitle() + "\n";
                text += "Число зрителей: " + channel.getViewers() + "\n";
                text += "Ссылка: " + channel.getLink() + "\n";
                sendMessage(text, chatId);
            }
        }


        class StreamMonitor extends Thread {
            public void run() {
                try {
                    while (true) {

                        for (Channel channel : channels) {
                            try {
                                Boolean status = channel.isOnline();
                                channel.update();
                                if (channel.isOnline() != status) {
                                    for (Long chatId : channel)
                                        sendAnnouncements(channel, chatId);
                                }
                            } catch (IOException e) {
                                System.out.print("error: update channel " + channel.getLink());
                            }
                        }
                        TimeUnit.SECONDS.sleep(UPDATE_DELAY);
                    }
                } catch (InterruptedException e) {

                }
            }
        }
    }

    static abstract class Channel implements Iterable<Long> {
        protected String name;
        protected boolean status;
        protected String broadcastTitle;
        protected String gameTitle;
        protected String viewers;
        protected String previewLink;

        protected HashSet<Long> chatsId = new HashSet<Long>();

        Channel(String channelName) {
            name = channelName;
        }

        public abstract String getPlatform();

        public abstract String getLink();

        public boolean isOnline() {
            return status;
        }

        public String getBroadcastTitle() {
            return broadcastTitle;
        }

        public String getGameTitle() {
            return gameTitle;
        }

        public String getViewers() {
            return viewers;
        }

        public String getPreviewLink() {
            return previewLink;
        }

        abstract void update() throws IOException;

        static protected String getStringFromUrl(String link) throws IOException {
            URL url = new URL(link);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(5000);
            connection.connect();
            InputStream inputStream = connection.getInputStream();
            //java.util.Scanner s = new java.util.Scanner(inputStream).useDelimiter("\\A");
            StringWriter writer = new StringWriter();
            IOUtils.copy(inputStream, writer, "UTF8");
            return writer.toString();
            //if (!s.hasNext())
            //    throw new IOException();
            //return s.next();
        }

        public void addChatId(Long chatId) {
            chatsId.add(chatId);
        }

        public boolean hasChatId(Long chatId) {
            return chatsId.contains(chatId);
        }

        @Override
        public Iterator<Long> iterator() {
            return chatsId.iterator();
        }
    }

    static class TwitchChannel extends Channel {

        TwitchChannel(String channelName) {
            super(channelName);
        }

        @Override
        public String getPlatform() {
            return "Twitch";
        }

        @Override
        public String getLink() {
            return "https://twitch.tv/" + name;
        }

        @Override
        void update() throws IOException {
            String link = "https://api.twitch.tv/kraken/streams/" + name + "?client_id=" + TWITCH_ID;
            String jsonText = getStringFromUrl(link);
            JsonElement jsonElement = new JsonParser().parse(jsonText);
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            if (!jsonObject.get("stream").isJsonNull()) {
                status = true;
                jsonElement = jsonObject.get("stream");
                jsonObject = jsonElement.getAsJsonObject();
                gameTitle = jsonObject.get("game").toString().replace("\"", "");
                broadcastTitle = jsonObject.get("channel").getAsJsonObject().get("status").toString().replace("\"", "");
                viewers = jsonObject.get("viewers").toString();
                previewLink = jsonObject.get("preview").getAsJsonObject().get("medium").toString().replace("\"", "");
            }
            else {
                status = false;
            }
        }
    }

    static class GoodGameChannel extends Channel {

        GoodGameChannel(String channelName) {
            super(channelName);
        }

        @Override
        public String getPlatform() {
            return "Good Game";
        }

        @Override
        public String getLink() {
            return "https://goodgame.ru/channel/" + name;
        }

        @Override
        void update() throws IOException {
            String link = "https://goodgame.ru/api/getchannelstatus?id=" + name + "&fmt=json";
            String jsonText = getStringFromUrl(link);
            JsonElement jsonElement = new JsonParser().parse(jsonText);
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            jsonElement = jsonObject.entrySet().iterator().next().getValue();
            jsonObject = jsonElement.getAsJsonObject();
            status = jsonObject.get("status").getAsString().contains("Live");
            broadcastTitle = jsonObject.get("title").getAsString();
            gameTitle = jsonObject.get("games").getAsString();
            viewers = jsonObject.get("viewers").toString().replace("\"", "");
            previewLink = jsonObject.get("img").toString().replace("\"","");
        }
    }
}