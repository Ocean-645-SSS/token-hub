package com.tokenhub.dto;
import java.util.List;
public record PageResponse<T>(List<T> records, long page, long size, long total) { }
