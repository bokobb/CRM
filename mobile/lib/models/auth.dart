const Map<String, String> roleLabels = {
  'ADMIN': 'Администратор',
  'SALES': 'Менеджер по продажам',
  'WAREHOUSE_FG': 'Кладовщик (склад ГП)',
  'ACCOUNTANT': 'Бухгалтер',
};

class AuthSession {
  final String token;
  final String username;
  final String fullName;
  final String role;
  final String roleLabel;

  AuthSession({
    required this.token,
    required this.username,
    required this.fullName,
    required this.role,
    required this.roleLabel,
  });

  factory AuthSession.fromJson(Map<String, dynamic> json) => AuthSession(
        token: json['token'] as String,
        username: json['username'] as String,
        fullName: json['fullName'] as String,
        role: json['role'] as String,
        roleLabel: json['roleLabel'] as String,
      );
}
