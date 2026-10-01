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
  final DateTime? birthDate;
  final bool fromOneC;

  /// Оклад в месяц и ставка за смену с учётом оклада; null — роль не видит.
  final double? monthlySalary;
  final double? effectiveDailyRate;

  /// Группа затрат: BRIGADE / PRODUCTION / SALES / ADMIN; [costGroupAuto] — определена по должности.
  final String costGroup;
  final String costGroupLabel;
  final bool costGroupAuto;

  Employee({
    required this.id,
    required this.fullName,
    this.position,
    this.phone,
    this.dailyRate,
    required this.active,
    this.iin,
    this.hiredAt,
    this.birthDate,
    this.fromOneC = false,
    this.monthlySalary,
    this.effectiveDailyRate,
    this.costGroup = 'BRIGADE',
    this.costGroupLabel = 'Бригада',
    this.costGroupAuto = true,
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
        birthDate: json['birthDate'] == null ? null : DateTime.parse(json['birthDate'] as String),
        fromOneC: json['fromOneC'] as bool? ?? false,
        monthlySalary: (json['monthlySalary'] as num?)?.toDouble(),
        effectiveDailyRate: (json['effectiveDailyRate'] as num?)?.toDouble(),
        costGroup: json['costGroup'] as String? ?? 'BRIGADE',
        costGroupLabel: json['costGroupLabel'] as String? ?? 'Бригада',
        costGroupAuto: json['costGroupAuto'] as bool? ?? true,
      );
}
