// Сотрудник цеха (меню «Сотрудники»). dailyRate == null — ставка не задана или роль её не видит.

class Employee {
  final int id;
  final String fullName;
  final String? position;
  final String? phone;
  final double? dailyRate;
  final bool active;
  final String? iin;
  final DateTime? hiredAt;
  final bool fromOneC;

  Employee({
    required this.id,
    required this.fullName,
    this.position,
    this.phone,
    this.dailyRate,
    required this.active,
    this.iin,
    this.hiredAt,
    this.fromOneC = false,
  });

  factory Employee.fromJson(Map<String, dynamic> json) => Employee(
        id: json['id'] as int,
        fullName: json['fullName'] as String,
        position: json['position'] as String?,
        phone: json['phone'] as String?,
        dailyRate: (json['dailyRate'] as num?)?.toDouble(),
        active: json['active'] as bool? ?? true,
        iin: json['iin'] as String?,
        hiredAt: json['hiredAt'] == null ? null : DateTime.parse(json['hiredAt'] as String),
        fromOneC: json['fromOneC'] as bool? ?? false,
      );
}
