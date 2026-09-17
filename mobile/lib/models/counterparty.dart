class Counterparty {
  final int id;
  final String name;

  /// Привязан к 1С (создан там при создании в CRM, либо загружен разовой синхронизацией на
  /// вебе) — см. counterparties_screen.dart.
  final bool oneCLinked;

  /// Причина, по которой создание/привязка к 1С не удалась — null, если проблем не было.
  final String? oneCSyncError;

  Counterparty({
    required this.id,
    required this.name,
    this.oneCLinked = false,
    this.oneCSyncError,
  });

  factory Counterparty.fromJson(Map<String, dynamic> json) => Counterparty(
        id: json['id'] as int,
        name: json['name'] as String,
        oneCLinked: json['oneCLinked'] as bool? ?? false,
        oneCSyncError: json['oneCSyncError'] as String?,
      );
}
