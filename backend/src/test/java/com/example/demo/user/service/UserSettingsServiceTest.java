package com.example.demo.user.service;

import com.example.demo.user.dto.UserSettingsDto;
import com.example.demo.user.repo.UserNotificationSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisabledInAotMode
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ba_settings;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class UserSettingsServiceTest {
    @Autowired UserSettingsService service;
    @Autowired UserNotificationSettingsRepository repository;

    @Test
    void preferences_are_persisted_and_control_delivery() {
        long userId = 99L;
        UserSettingsDto defaults = service.getSettings(userId);
        assertThat(defaults.getInApp().isReply()).isTrue();
        assertThat(defaults.getInApp().isFollow()).isTrue();

        defaults.getInApp().setReply(false);
        defaults.getInApp().setFollow(false);
        service.updateSettings(userId, defaults);
        assertThat(repository.findById(userId)).isPresent();

        UserSettingsService restarted = new UserSettingsService(repository);
        assertThat(restarted.getSettings(userId).getInApp().isReply()).isFalse();
        assertThat(restarted.shouldDeliver(userId, "reply")).isFalse();
        assertThat(restarted.shouldDeliver(userId, "follow")).isFalse();
        assertThat(restarted.shouldDeliver(userId, "mention")).isTrue();
    }

    @Test
    void unavailable_email_and_system_choices_are_rejected() {
        UserSettingsDto settings = new UserSettingsDto();
        settings.getEmail().setEnabled(true);
        assertThatThrownBy(() -> service.updateSettings(100L, settings))
                .isInstanceOf(ResponseStatusException.class);
        settings.getEmail().setEnabled(false);
        settings.getInApp().setSystem(false);
        assertThatThrownBy(() -> service.updateSettings(100L, settings))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(repository.findById(100L)).isEmpty();
    }
}
