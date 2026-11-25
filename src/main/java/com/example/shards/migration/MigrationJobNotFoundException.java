package com.example.shards.migration;

final class MigrationJobNotFoundException extends RuntimeException {

    MigrationJobNotFoundException() {
        super("재배치 작업을 찾을 수 없습니다.");
    }
}
