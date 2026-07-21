package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IStatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/statistics")
public class StatisticsController {

    @Resource
    private IStatisticsService statisticsService;

    @GetMapping("/blog/hot/uv")
    public Result queryBlogHotUv() {
        return statisticsService.queryBlogHotUv();
    }
}
