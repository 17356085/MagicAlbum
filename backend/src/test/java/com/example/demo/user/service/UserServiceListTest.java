package com.example.demo.user.service;

import com.example.demo.auth.service.otp.AuthVerifyService;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserServiceListTest {
    private UserRepository userRepository;
    private UserProfileRepository userProfileRepository;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userProfileRepository = mock(UserProfileRepository.class);
        service = new UserService(
                userRepository,
                userProfileRepository,
                mock(PasswordEncoder.class),
                mock(AuthVerifyService.class)
        );
    }

    @Test
    void list_batches_profiles_and_maps_them_by_user_id_without_changing_page_order() {
        User first = user(11L, "first");
        User second = user(22L, "second");
        when(userRepository.searchPublic(null, true, true, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));

        UserProfile secondProfile = profile(22L, "second nickname", "second-avatar");
        UserProfile firstProfile = profile(11L, "first nickname", "first-avatar");
        when(userProfileRepository.findByUserIdIn(List.of(11L, 22L)))
                .thenReturn(List.of(secondProfile, firstProfile));

        var result = service.list(null, 1, 20, null);

        assertThat(result.getContent()).extracting(UserSummaryDto::getId)
                .containsExactly(11L, 22L);
        assertThat(result.getContent()).extracting(UserSummaryDto::getNickname)
                .containsExactly("first nickname", "second nickname");
        assertThat(result.getContent()).extracting(UserSummaryDto::getAvatarUrl)
                .containsExactly("first-avatar", "second-avatar");
        verify(userProfileRepository).findByUserIdIn(List.of(11L, 22L));
        verify(userProfileRepository, never()).findById(any());
    }

    @Test
    void list_keeps_user_summary_when_profile_is_missing() {
        User user = user(31L, "without-profile");
        when(userRepository.searchPublic(null, true, true, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(userProfileRepository.findByUserIdIn(List.of(31L))).thenReturn(List.of());

        var result = service.list(null, 1, 20, null);

        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.getId()).isEqualTo(31L);
            assertThat(summary.getUsername()).isEqualTo("without-profile");
            assertThat(summary.getNickname()).isNull();
            assertThat(summary.getAvatarUrl()).isNull();
        });
    }

    @Test
    void list_does_not_query_profiles_for_an_empty_page_and_preserves_metadata() {
        when(userRepository.searchPublic("alice", true, true, PageRequest.of(2, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 40));

        var result = service.list(" alice ", 3, 20, null);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isEqualTo(40);
        verify(userProfileRepository, never()).findByUserIdIn(any());
    }

    @Test
    void list_uses_selected_public_fields_and_clamps_page_and_size() {
        User user = user(41L, "nickname-match");
        when(userRepository.searchPublic(eq("nick"), eq(true), eq(true), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(userProfileRepository.findByUserIdIn(List.of(41L))).thenReturn(List.of());

        var result = service.list(" nick ", 0, 100, "username,nickname");

        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(20);
        verify(userRepository).searchPublic("nick", true, true, PageRequest.of(0, 20));
        verify(userRepository, never()).search(any(), any());
    }

    @Test
    void list_clamps_nonpositive_page_size_to_one() {
        when(userRepository.searchPublic(null, true, true, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        var result = service.list(null, -1, 0, null);

        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(1);
        verify(userProfileRepository, never()).findByUserIdIn(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"notnickname", "username,unknown", "username,,nickname", "email", "phone"})
    void list_rejects_unknown_or_empty_field_names_before_reading_users(String fields) {
        assertThatThrownBy(() -> service.list("nick", 1, 20, fields))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error)
                        .getStatusCode().value()).isEqualTo(400));

        verifyNoInteractions(userRepository, userProfileRepository);
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return user;
    }

    private static UserProfile profile(Long userId, String nickname, String avatarUrl) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setNickname(nickname);
        profile.setAvatarUrl(avatarUrl);
        return profile;
    }
}
