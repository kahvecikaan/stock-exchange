package com.stockexchange.stock_platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChartDataDto {
    private String title;
    private String xAxisLabel;
    private String yAxisLabel;
    private List<String> labels; // X-axis labels (dates/times)
    private Map<String, List<Double>> datasets; // Series name -> data points
}