package com.scalablehrms.leave.config;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component
@ConfigurationProperties(prefix="hrms.reminders")
public class ReminderPolicy {
    private boolean enabled=true;
    private int afterHours=48,repeatHours=24;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled=enabled; }
    public int getAfterHours() { return afterHours; }
    public void setAfterHours(int hours) { this.afterHours=hours; }
    public int getRepeatHours() { return repeatHours; }
    public void setRepeatHours(int hours) { this.repeatHours=hours; }
    @PostConstruct public void validate() {
        if (afterHours<1 || repeatHours<1) throw new IllegalArgumentException("Reminder intervals must be positive");
    }
}
