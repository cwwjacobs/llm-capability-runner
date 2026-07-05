package com.terminus.edge.light.ledger

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [LedgerMessageEntity::class], version = 2, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
  abstract fun ledgerDao(): LedgerDao
}
