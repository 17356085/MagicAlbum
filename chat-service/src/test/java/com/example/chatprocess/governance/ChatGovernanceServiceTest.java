package com.example.chatprocess.governance;

import com.example.demo.chat.repo.ChatRepository;
import com.example.demo.chat.service.ChatChangePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatGovernanceServiceTest {
    private static final long MESSAGE_ID = 41L;
    private static final long REPORT_ID = 51L;
    private static final long ROOM_ID = 61L;
    private static final ChatRepository.Room ROOM = new ChatRepository.Room(
            ROOM_ID, 7L, 8L, null, null, "ACTIVE", 2L, 4L);

    @Mock ChatRepository chat;
    @Mock ChatGovernanceReceiptRepository receipts;
    @Mock ChatChangePublisher realtime;

    private ChatGovernanceService service;

    @BeforeEach
    void setUp() {
        service = new ChatGovernanceService(chat, receipts, realtime);
        lenient().when(chat.messageRoom(MESSAGE_ID)).thenReturn(Optional.of(ROOM_ID));
        lenient().when(chat.room(ROOM_ID, true)).thenReturn(Optional.of(ROOM));
        lenient().when(receipts.findDecision(any())).thenReturn(Optional.empty());
        lenient().when(receipts.findDecisionForUpdate(any())).thenReturn(Optional.empty());
        lenient().when(receipts.findVersionForUpdate(anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void newer_decision_applies_once_and_notifies_after_real_visibility_change() {
        UUID decision = UUID.randomUUID();
        when(receipts.latestVersion(MESSAGE_ID)).thenReturn(Optional.of(1L));
        when(chat.setHidden(MESSAGE_ID, true)).thenReturn(true);
        ChatGovernanceService.Receipt result = service.apply(decision, 2L, MESSAGE_ID, REPORT_ID, true);

        assertThat(result.outcome()).isEqualTo(ChatGovernanceService.Outcome.APPLIED);
        verify(chat).advanceHistoryRevision(ROOM_ID);
        verify(realtime).changed(any());
        verify(receipts).insert(decision, MESSAGE_ID, REPORT_ID, 2L, true,
                ChatGovernanceService.Outcome.APPLIED);
    }

    @Test
    void older_out_of_order_decision_is_recorded_stale_without_mutation() {
        UUID decision = UUID.randomUUID();
        when(receipts.latestVersion(MESSAGE_ID)).thenReturn(Optional.of(4L));

        ChatGovernanceService.Receipt result = service.apply(decision, 3L, MESSAGE_ID, REPORT_ID, true);

        assertThat(result.outcome()).isEqualTo(ChatGovernanceService.Outcome.STALE);
        verify(chat, never()).setHidden(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(chat, never()).advanceHistoryRevision(anyLong());
        verify(realtime, never()).changed(any());
        verify(receipts).insert(decision, MESSAGE_ID, REPORT_ID, 3L, true,
                ChatGovernanceService.Outcome.STALE);
    }

    @Test
    void exact_replay_returns_durable_outcome_without_touching_chat() {
        UUID decision = UUID.randomUUID();
        when(receipts.findDecision(decision)).thenReturn(Optional.of(
                new ChatGovernanceReceiptRepository.Receipt(decision, MESSAGE_ID, REPORT_ID, 2L, true,
                        ChatGovernanceService.Outcome.APPLIED)));

        ChatGovernanceService.Receipt result = service.apply(decision, 2L, MESSAGE_ID, REPORT_ID, true);

        assertThat(result.outcome()).isEqualTo(ChatGovernanceService.Outcome.APPLIED);
        verify(chat, never()).messageRoom(anyLong());
        verify(receipts, never()).insert(any(), anyLong(), anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void reused_decision_id_with_different_tuple_is_conflict() {
        UUID decision = UUID.randomUUID();
        when(receipts.findDecision(decision)).thenReturn(Optional.of(
                new ChatGovernanceReceiptRepository.Receipt(decision, MESSAGE_ID, REPORT_ID, 2L, true,
                        ChatGovernanceService.Outcome.APPLIED)));

        assertThatThrownBy(() -> service.apply(decision, 2L, MESSAGE_ID, REPORT_ID + 1, true))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
    }
}
