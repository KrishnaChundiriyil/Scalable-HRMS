package com.scalablehrms.leave.dto;
import java.util.List;
public record PageResult<T>(List<T> items,int page,int size,long totalElements,long totalPages) {
    public static <T> PageResult<T> of(List<T> items,int page,int size,long total) {
        return new PageResult<>(items,page,size,total,(total+size-1)/size);
    }
}
