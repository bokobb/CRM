class CompanyProfile {
  final String? name;
  final String? address;
  final String? phone;
  final String? email;

  /// Только для чтения (бланк «Объём и масса»): склад как в «Реализации» 1С и кладовщик.
  final String? warehouseName;
  final String? warehouseManagerName;

  CompanyProfile({this.name, this.address, this.phone, this.email, this.warehouseName, this.warehouseManagerName});

  factory CompanyProfile.fromJson(Map<String, dynamic> json) => CompanyProfile(
        name: json['name'] as String?,
        address: json['address'] as String?,
        phone: json['phone'] as String?,
        email: json['email'] as String?,
        warehouseName: json['warehouseName'] as String?,
        warehouseManagerName: json['warehouseManagerName'] as String?,
      );

  Map<String, dynamic> toJson() => {'name': name, 'address': address, 'phone': phone, 'email': email};
}
