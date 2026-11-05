package kr.ac.chosun.mspace.assistant.mcp.room.application;

import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;

/** 코어 방 현황 (GET /api/rooms/status, FR-DEV-102). 지금 이 순간 어느 방이 비었는지 */
public interface RoomStatusPort {

    RoomsNow status(UserCredential credential);
}
