package kr.ac.chosun.mspace.assistant.mcp.room.application;

import java.time.LocalDate;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;

public interface RoomAvailabilityPort {

    RoomDay findDay(LocalDate date, UserCredential credential);
}
