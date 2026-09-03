class AppUser {
  final int id;
  final String username;
  final String fullName;
  final String role;
  final String roleLabel;
  final bool enabled;
  final DateTime createdAt;

  AppUser({
    required this.id,
    required this.username,
    required this.fullName,
    required this.role,
    required this.roleLabel,
    required this.enabled,
    required this.createdAt,
  });

  factory AppUser.fromJson(Map<String, dynamic> json) => AppUser(
        id: json['id'] as int,
        username: json['username'] as String,
        fullName: json['fullName'] as String,
        role: json['role'] as String,
        roleLabel: json['roleLabel'] as String,
        enabled: json['enabled'] as bool,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

const List<String> userRoles = ['ADMIN', 'SALES', 'WAREHOUSE_FG', 'WAREHOUSE_RAW', 'ACCOUNTANT'];

const Map<String, String> userRoleLabels = {
  'ADMIN': 'Администратор',
  'SALES': 'Менеджер по продажам',
  'WAREHOUSE_FG': 'Кладовщик (склад ГП)',
  'WAREHOUSE_RAW': 'Кладовщик (склад сырья)',
  'ACCOUNTANT': 'Бухгалтер',
};
