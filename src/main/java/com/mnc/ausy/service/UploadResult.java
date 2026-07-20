package com.mnc.ausy.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class UploadResult {
    public boolean success;
    public String message;
    public String paket;
    public int rows;
    public BigDecimal debit = BigDecimal.ZERO;
    public BigDecimal credit = BigDecimal.ZERO;
    public boolean balanced;
    public final List<String[]> errors = new ArrayList<>();   // [rowNo, message]
}
