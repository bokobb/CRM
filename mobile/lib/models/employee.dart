// Сотрудник цеха (меню «Сотрудники»). dailyRate == null — ставка не задана или роль её не видит.

class Employee {
  final int id;
  final String fullName;
  final String? position;
  final String? phone;
  final double? dailyRate;
  final bool active;

  Employee({
    required this.id,
    required this.fullName,
    this.position,
    this.phone,
    this.dailyRate,
    required this.active,
  });

  factory Employee.fromJson(Map<String, dynamic> json) => Employee(
        id: json['id'] as int,
        fullName: json['fullName'] as String,
        position: json['position'] as String?,
        phone: json['phone'] as String?,
        dailyRate: (json['dailyRate'] as num?)?.toDouble(),
        active: json['active'] as bool? ?? true,
      );
}
