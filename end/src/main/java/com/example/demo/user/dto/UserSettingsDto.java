package com.example.demo.user.dto;

public class UserSettingsDto {
    public static class InApp {
        private boolean reply = true;
        private boolean mention = true;
        private boolean like = true;
        private boolean system = true;
        public boolean isReply() { return reply; }
        public void setReply(boolean reply) { this.reply = reply; }
        public boolean isMention() { return mention; }
        public void setMention(boolean mention) { this.mention = mention; }
        public boolean isLike() { return like; }
        public void setLike(boolean like) { this.like = like; }
        public boolean isSystem() { return system; }
        public void setSystem(boolean system) { this.system = system; }
    }

    public static class Email {
        private boolean enabled = false;
        private String frequency = "instant";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFrequency() { return frequency; }
        public void setFrequency(String frequency) { this.frequency = frequency; }
    }

    private InApp inApp = new InApp();
    private Email email = new Email();

    public InApp getInApp() { return inApp; }
    public void setInApp(InApp inApp) { this.inApp = inApp; }
    public Email getEmail() { return email; }
    public void setEmail(Email email) { this.email = email; }
}