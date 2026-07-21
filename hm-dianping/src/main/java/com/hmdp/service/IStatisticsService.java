package com.hmdp.service;

import com.hmdp.dto.Result;

import javax.servlet.http.HttpServletRequest;

public interface IStatisticsService {

    void recordBlogHotVisitor(HttpServletRequest request);

    Result queryBlogHotUv();
}
