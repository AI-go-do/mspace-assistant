package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.time.OffsetDateTime;

/** 학생 확인을 기다리는 쓰기 작업. 모델이 고른 내용 그대로이고, 학번은 들어 있지 않다 (A-5) */
public sealed interface PendingAction {

    /** 예약 만들기. 참여자 학번(headcount − 1명)은 확인 카드에서 따로 받는다 */
    record Create(int roomId, OffsetDateTime startAt, OffsetDateTime endAt, int headcount) implements PendingAction {
    }

    record Cancel(long reservationId) implements PendingAction {
    }

    record Checkout(long reservationId) implements PendingAction {
    }
}
