package kr.ac.chosun.mspace.assistant.mcp.campus.adapter.out.gjbis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 도착 응답 해석: 정상 0건과 해석 실패를 가른다 */
class GwangjuBusClientTest {

    final GwangjuBusClient client = new GwangjuBusClient(
            new CampusProperties(null, null, new CampusProperties.CityBus("", null, null, null, null, null, null, null, null)),
            JsonMapper.builder().build(), Clock.systemUTC());

    @Test
    @DisplayName("0건 응답은 \"오는 버스 없음\"(빈 목록)")
    void zeroRows() {
        assertThat(client.parseArrivals("{\"ROW_COUNT\":0}")).hasValueSatisfying(b -> assertThat(b).isEmpty());
    }

    @Test
    @DisplayName("N건이라면서 목록이 없거나, 모르는 모양이면 실패로 본다 (\"오는 버스 없음\"으로 바꾸지 않음)")
    void brokenShapes() {
        assertThat(client.parseArrivals("{\"ROW_COUNT\":2}")).isEmpty();
        assertThat(client.parseArrivals("{\"error\":\"SERVICE_KEY_IS_NOT_REGISTERED\"}")).isEmpty();
        assertThat(client.parseArrivals("{\"ROW_COUNT\":1,\"BUSSTOP_LIST\":[{\"REMAIN_MIN\":3}]}")).isEmpty();
    }

    @Test
    @DisplayName("항목이 하나면 객체로 와도 읽고, 남은 분 순으로 정렬한다")
    void singleObjectAndSort() {
        assertThat(client.parseArrivals("{\"ROW_COUNT\":1,\"BUSSTOP_LIST\":{\"LINE_NAME\":\"419\",\"REMAIN_MIN\":\"4\"}}"))
                .hasValueSatisfying(b -> assertThat(b).extracting("line", "minutes").containsExactly(org.assertj.core.groups.Tuple.tuple("419", 4)));
    }
}
