package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.YardSlot;
import com.port.terminal.domain.YardZone;
import com.port.terminal.dto.SlotActionRequest;
import com.port.terminal.dto.ZoneOccupancyDTO;
import com.port.terminal.service.YardService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yard")
public class YardController {
    private final YardService yardService;

    public YardController(YardService yardService) {
        this.yardService = yardService;
    }

    @GetMapping("/zones")
    public ApiResponse<List<YardZone>> zones() { return ApiResponse.success(yardService.zones()); }

    @GetMapping("/slots")
    public ApiResponse<List<YardSlot>> slots(@RequestParam String zone,
                                             @RequestParam(defaultValue = "1") Integer bayStart,
                                             @RequestParam(defaultValue = "99") Integer bayEnd) {
        return ApiResponse.success(yardService.slots(zone, bayStart, bayEnd));
    }

    @GetMapping("/slots/{zone}/{bay}/{row}/{tier}")
    public ApiResponse<YardSlot> exact(@PathVariable String zone, @PathVariable Integer bay,
                                       @PathVariable Integer row, @PathVariable Integer tier) {
        return ApiResponse.success(yardService.exactSlot(zone, bay, row, tier));
    }

    @GetMapping("/matrix/{zone}/{bay}")
    public ApiResponse<List<YardSlot>> matrix(@PathVariable String zone, @PathVariable Integer bay) {
        return ApiResponse.success(yardService.bayMatrix(zone, bay));
    }

    @GetMapping("/occupancy")
    public ApiResponse<List<ZoneOccupancyDTO>> occupancy() { return ApiResponse.success(yardService.occupancy()); }

    @GetMapping("/bay-stats")
    public ApiResponse<Map<String, Object>> bayStats(@RequestParam String zone, @RequestParam Integer bay) {
        return ApiResponse.success(yardService.bayStats(zone, bay));
    }

    @GetMapping("/tier-stats")
    public ApiResponse<Map<String, Object>> tierStats(@RequestParam Integer tier) {
        return ApiResponse.success(yardService.tierStats(tier));
    }

    @PostMapping("/place")
    public ApiResponse<Container> place(@Valid @RequestBody SlotActionRequest request) {
        return ApiResponse.success("放箱成功", yardService.place(request));
    }

    @PostMapping("/relocate")
    public ApiResponse<Container> relocate(@Valid @RequestBody SlotActionRequest request) {
        return ApiResponse.success("翻倒完成", yardService.relocate(request));
    }

    @PostMapping("/pickup/{containerNo}")
    public ApiResponse<Container> pickup(@PathVariable String containerNo) {
        return ApiResponse.success("提箱出场完成", yardService.pickup(containerNo));
    }
}
