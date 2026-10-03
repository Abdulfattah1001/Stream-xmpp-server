# Changelog

All notable changes to this project are documented here

## [1.3.0] - 2026-10-3

### Added
- Added message delivery timestamp support
- Added `delivery_at` column to the offline_messages table

### Database
- Migration: `V3_add_delivered_at.sql`
- Migration is automatically applied during deployment

### Changed 
- Message delivery processing now records he delivery timestamp


### Fixed
- Fixed message delivery status not being persisted correctly.