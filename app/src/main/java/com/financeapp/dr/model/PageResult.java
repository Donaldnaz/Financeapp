package com.financeapp.dr.model;

import java.util.List;

public record PageResult<T>(
        List<T> items,
        String nextCursor
) {
}
